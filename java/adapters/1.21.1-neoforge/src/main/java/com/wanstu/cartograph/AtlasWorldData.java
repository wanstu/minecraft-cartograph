package com.wanstu.cartograph;

import com.wanstu.cartograph.core.SurveyLedger;
import com.wanstu.cartograph.core.SpiralCoverage;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * World authoritative atlas catalog and paid-chunk journal.
 * Render data and all balances are saved together in one SavedData NBT file.
 * Inventory components mirror balances; old/doubled item copies cannot refund
 * spent credits. Only the server mutates the journal.
 */
public final class AtlasWorldData extends SavedData {
    private static final SavedData.Factory<AtlasWorldData> FACTORY =
            new SavedData.Factory<>(AtlasWorldData::new, AtlasWorldData::load);
    private static final String STORAGE = "cartograph_atlases";
    private final Map<String, Entry> entries = new HashMap<>();

    public static final class Entry {
        private AtlasState state;
        private String anchorDimension = "";
        private int anchorX, anchorZ;
        private final Map<SurveyLedger.ChunkKey, byte[]> pixels = new HashMap<>();
        private final Map<SurveyLedger.ChunkKey, byte[]> features = new HashMap<>();
        private final Map<String, AtlasMarker> markers = new HashMap<>();
        private SurveyLedger ledger = new SurveyLedger();

        private Entry(AtlasState state) { this.state = state; }
        public AtlasState state() { return state; }

        public boolean anchored() { return !anchorDimension.isEmpty(); }
        public int anchorX() { return anchorX; }
        public int anchorZ() { return anchorZ; }
        public void setAnchor(String dim, int x, int z) {
            if (!anchored()) { anchorDimension = dim; anchorX = x; anchorZ = z; }
        }
        public boolean isCovered(String dim, int pageX, int pageZ) {
            return dim.equals(anchorDimension) && SpiralCoverage.covers(anchorX, anchorZ,
                    pageX, pageZ, state.pages());
        }
        public byte[] getPixels(SurveyLedger.ChunkKey key) { return pixels.get(key); }
        public byte[] getFeatures(SurveyLedger.ChunkKey key) { return features.get(key); }
        public boolean isColored(SurveyLedger.ChunkKey key) {
            return ledger.isColored(key);
        }
        public boolean paid(SurveyLedger.ChunkKey key) { return ledger.updateMayRender(key); }
        public AtlasMarker getMarker(String id) { return markers.get(id); }
        public int markerCount() { return markers.size(); }
        public int markerCountOnPage(String dimension, int px, int pz) {
            int count = 0;
            for (AtlasMarker marker : markers.values()) {
                if (marker.dimension().equals(dimension)
                        && Math.floorDiv(marker.x(), 128) == px
                        && Math.floorDiv(marker.z(), 128) == pz) count++;
            }
            return count;
        }
        public List<AtlasMarker> markersOnPage(String dimension, int px, int pz) {
            List<AtlasMarker> result = new ArrayList<>();
            for (AtlasMarker marker : markers.values()) {
                if (marker.dimension().equals(dimension)
                        && Math.floorDiv(marker.x(), 128) == px
                        && Math.floorDiv(marker.z(), 128) == pz) result.add(marker);
            }
            result.sort(java.util.Comparator.comparing(AtlasMarker::id));
            return List.copyOf(result);
        }
        private void putMarker(AtlasMarker marker) { markers.put(marker.id(), marker); }
        private void deleteMarker(String id) { markers.remove(id); }
        private void setState(AtlasState s) { state = s; }

        private boolean sample(SurveyLedger.ChunkKey key, byte[] pixelData, byte[] featureData) {
            if (pixelData == null || pixelData.length != 256
                    || featureData == null || featureData.length != 256) return false;
            SurveyLedger.Result result = ledger.survey(key, true, true,
                    state.inkEnabled(), state.colorEnabled(),
                    state.inkUnits(), state.pigmentUnits(),
                    CartographConfig.inkCostUnits(), CartographConfig.pigmentCostUnits());
            if (!result.fullySurveyed()) return false;
            if (result.firstSurvey()) state = state.payInk(result.inkCostUnits());
            if (result.firstColor()) state = state.payPigment(result.pigmentCostUnits());
            byte[] previous = pixels.put(key, pixelData.clone());
            byte[] oldFeatures = features.put(key, featureData.clone());
            return result.firstSurvey() || result.firstColor() || !java.util.Arrays.equals(previous, pixelData)
                    || !java.util.Arrays.equals(oldFeatures, featureData);
        }

        private CompoundTag saveEntry() {
            CompoundTag out = new CompoundTag();
            out.put("state", AtlasState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow());
            out.putString("anchor_dim", anchorDimension);
            out.putInt("anchor_x", anchorX); out.putInt("anchor_z", anchorZ);
            Set<SurveyLedger.ChunkKey> colored = ledger.snapshot().colorized();
            ListTag chunks = new ListTag();
            for (Map.Entry<SurveyLedger.ChunkKey, byte[]> line : pixels.entrySet()) {
                CompoundTag c = new CompoundTag(); var key = line.getKey();
                c.putString("dim", key.dimension()); c.putInt("x", key.x()); c.putInt("z", key.z());
                c.putByteArray("colors", line.getValue()); c.putBoolean("colored", colored.contains(key));
                byte[] feature = features.get(key);
                if (feature != null) c.putByteArray("features", feature);
                chunks.add(c);
            }
            out.put("chunks", chunks);
            ListTag markersTag = new ListTag();
            for (AtlasMarker marker : markers.values()) markersTag.add(marker.save());
            out.put("markers", markersTag);
            return out;
        }

        private static Entry loadEntry(CompoundTag tag) {
            AtlasState state = AtlasState.CODEC.parse(NbtOps.INSTANCE, tag.get("state"))
                    .result().orElse(AtlasState.blank());
            Entry entry = new Entry(state);
            entry.anchorDimension = tag.getString("anchor_dim");
            entry.anchorX = tag.getInt("anchor_x"); entry.anchorZ = tag.getInt("anchor_z");
            Set<SurveyLedger.ChunkKey> paid = new HashSet<>(), color = new HashSet<>();
            ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                byte[] colors = c.getByteArray("colors");
                if (colors.length != 256) continue;
                String dim = c.getString("dim");
                if (dim.isBlank()) continue;
                var key = new SurveyLedger.ChunkKey(dim, c.getInt("x"), c.getInt("z"));
                paid.add(key);
                if (c.getBoolean("colored")) color.add(key);
                entry.pixels.put(key, colors);
                byte[] feature = c.getByteArray("features");
                if (feature.length == 256) entry.features.put(key, feature);
            }
            entry.ledger = new SurveyLedger(new SurveyLedger.Snapshot(paid, color));
            ListTag markerTags = tag.getList("markers", Tag.TAG_COMPOUND);
            for (int i = 0; i < markerTags.size(); i++) {
                AtlasMarker marker = AtlasMarker.read(markerTags.getCompound(i));
                if (marker != null) entry.markers.put(marker.id(), marker);
            }
            return entry;
        }
    }

    private static AtlasWorldData load(CompoundTag tag, HolderLookup.Provider provider) {
        AtlasWorldData result = new AtlasWorldData();
        ListTag list = tag.getList("atlases", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompound(i);
            String id = item.getString("id");
            if (!id.isBlank()) result.entries.put(id, Entry.loadEntry(item));
        }
        return result;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            CompoundTag compound = e.getValue().saveEntry();
            compound.putString("id", e.getKey());
            list.add(compound);
        }
        tag.put("atlases", list);
        return tag;
    }

    public static AtlasWorldData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, STORAGE);
    }

    public Entry access(ItemStack atlas) {
        AtlasState item = atlas.getOrDefault(CartographMod.ATLAS_STATE.get(), AtlasState.blank()).identified();
        Entry saved = entries.get(item.id());
        if (saved == null) {
            saved = new Entry(item);
            entries.put(item.id(), saved);
            setDirty();
        } else if (item.pages() > saved.state.pages()) {
            // Only map-count growth can be mirrored from crafting; credits stay authoritative.
            AtlasState s = saved.state;
            saved.state = new AtlasState(s.id(), item.pages(), s.inkUnits(), s.pigmentUnits(), s.glowUnits(),
                    s.redDyes(), s.yellowDyes(), s.blueDyes(),
                    s.inkEnabled(), s.colorEnabled(), s.glowEnabled(), s.compassInstalled());
            setDirty();
        }
        if (!saved.state.equals(atlas.get(CartographMod.ATLAS_STATE.get()))) {
            atlas.set(CartographMod.ATLAS_STATE.get(), saved.state);
        }
        return saved;
    }

    public void update(Entry entry, AtlasState newState, ItemStack item) {
        entry.setState(newState);
        item.set(CartographMod.ATLAS_STATE.get(), entry.state());
        setDirty();
    }

    public boolean sample(Entry entry, SurveyLedger.ChunkKey key, byte[] bytes,
                          byte[] features, ItemStack item) {
        boolean changed = entry.sample(key, bytes, features);
        if (changed) {
            item.set(CartographMod.ATLAS_STATE.get(), entry.state());
            setDirty();
        }
        return changed;
    }

    public void putMarker(Entry entry, AtlasMarker marker, ItemStack atlas) {
        entry.putMarker(marker);
        atlas.set(CartographMod.ATLAS_STATE.get(), entry.state());
        setDirty();
    }

    public void deleteMarker(Entry entry, String id) {
        entry.deleteMarker(id);
        setDirty();
    }

    public void anchor(Entry entry, String dimension, int pageX, int pageZ) {
        if (!entry.anchored()) {
            entry.setAnchor(dimension, pageX, pageZ);
            setDirty();
        }
    }
}
