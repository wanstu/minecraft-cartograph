package com.wanstu.cartograph;

import com.wanstu.cartograph.core.SurveyLedger;
import com.wanstu.cartograph.core.MapSheetCoverage;
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
 * Coverage masks, annotations and balances are saved together in one SavedData NBT file.
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
        // Locked to each atlas when created; legacy records gain the generous default.
        private int sheetSideBlocks = MapSheetCoverage.DEFAULT_BLOCKS;
        // Legacy pages keep their original spiral positions. New paper sheets
        // may be placed in the current distant map area, without buying
        // thousands of intervening empty spiral sheets.
        public record PaperSite(int pageX, int pageZ) {}
        private int legacySheets;
        private final List<PaperSite> remoteSheets = new ArrayList<>();
        private final Map<String, AtlasMarker> markers = new HashMap<>();
        private final Map<String, NamedRegion> namedRegions = new HashMap<>();
        private SurveyLedger ledger = new SurveyLedger();

        private Entry(AtlasState state) {
            this(state, CartographConfig.pageSideBlocks()); // new atlases only
        }
        private Entry(AtlasState state, int side) {
            this.state = state;
            this.sheetSideBlocks = side;
            this.legacySheets = state.pages();
        }
        public AtlasState state() { return state; }

        public boolean anchored() { return !anchorDimension.isEmpty(); }
        public int anchorX() { return anchorX; }
        public int anchorZ() { return anchorZ; }
        public int sheetSideBlocks() { return sheetSideBlocks; }
        public int legacySheets() { return legacySheets; }
        public List<PaperSite> remoteSheets() { return List.copyOf(remoteSheets); }
        /** New sheets are placed at the actual requested area, not silently
         * appended 10,000 blocks away to a spiral centered on spawn. */
        public void placeExtraSheet(String dim, int pageX, int pageZ) {
            if (!dim.equals(anchorDimension) || legacySheets + remoteSheets.size() >= AtlasState.MAX_PAGES)
                throw new IllegalArgumentException("Cannot place paper outside atlas world or max capacity");
            if (isCovered(dim, pageX, pageZ)) {
                legacySheets++;
            } else {
                remoteSheets.add(new PaperSite(pageX, pageZ));
            }
        }
        public void setAnchor(String dim, int x, int z) {
            if (!anchored()) { anchorDimension = dim; anchorX = x; anchorZ = z; }
        }
        public boolean isCovered(String dim, int pageX, int pageZ) {
            if (!dim.equals(anchorDimension)) return false;
            if (MapSheetCoverage.covers(anchorX, anchorZ,
                    pageX, pageZ, legacySheets, sheetSideBlocks)) return true;
            for (PaperSite site : remoteSheets)
                if (MapSheetCoverage.covers(site.pageX(), site.pageZ(),
                        pageX, pageZ, 1, sheetSideBlocks)) return true;
            return false;
        }
        public String anchorDimension() { return anchorDimension; }
        public int surveyedCount() { return ledger.surveyedCount(); }
        public java.util.Set<SurveyLedger.ChunkKey> surveyedKeys(String dimension) {
            java.util.Set<SurveyLedger.ChunkKey> keys = new java.util.HashSet<>();
            for (SurveyLedger.ChunkKey k : ledger.snapshot().surveyed())
                if (k.dimension().equals(dimension)) keys.add(k);
            return java.util.Set.copyOf(keys);
        }
        public int coloredCount() { return ledger.colorizedCount(); }
        public boolean isColored(SurveyLedger.ChunkKey key) {
            return ledger.isColored(key);
        }
        public boolean paid(SurveyLedger.ChunkKey key) { return ledger.updateMayRender(key); }
        public AtlasMarker getMarker(String id) { return markers.get(id); }
        public int markerCount() { return markers.size(); }
        public List<AtlasMarker> markers(String dimension) {
            return markers.values().stream().filter(marker -> marker.dimension().equals(dimension)).toList();
        }
        public int regionCount() { return namedRegions.size(); }
        public NamedRegion getRegion(String id) { return namedRegions.get(id); }
        public List<NamedRegion> regions(String dimension) {
            List<NamedRegion> result = new ArrayList<>();
            for (NamedRegion region : namedRegions.values())
                if (region.dimension().equals(dimension)) result.add(region);
            result.sort(java.util.Comparator.comparing(NamedRegion::id));
            return List.copyOf(result);
        }
        private void putRegion(NamedRegion region) { namedRegions.put(region.id(), region); }
        private void deleteRegion(String id) { namedRegions.remove(id); }
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

        private boolean reveal(SurveyLedger.ChunkKey key) {
            SurveyLedger.Result result = ledger.survey(key, true, true,
                    state.inkEnabled(), state.colorEnabled(), state.inkUnits(), state.pigmentUnits(),
                    CartographConfig.cost(0), CartographConfig.cost(1),
                    CartographConfig.permanent(0), CartographConfig.permanent(1));
            if (result.inkCostUnits() > 0) state = state.payInk(result.inkCostUnits());
            if (result.pigmentCostUnits() > 0) state = state.payPigment(result.pigmentCostUnits());
            return result.firstSurvey() || result.firstColor();
        }

        /** Dye-only reconciliation: no ink use, no terrain rescan and no
         * ability to create survey entitlement. Valid only for generated
         * chunks inside a verified paper page. */
        private boolean colorize(SurveyLedger.ChunkKey key) {
            SurveyLedger.Result result = ledger.colorize(key, true, true,
                    state.colorEnabled(), state.pigmentUnits(),
                    CartographConfig.cost(1), CartographConfig.permanent(1));
            if (result.pigmentCostUnits() > 0)
                state = state.payPigment(result.pigmentCostUnits());
            return result.firstColor();
        }

        private static ListTag saveMask(Set<SurveyLedger.ChunkKey> keys) {
            ListTag out = new ListTag();
            for (SurveyLedger.ChunkKey key : keys) {
                CompoundTag tag = new CompoundTag();
                tag.putString("dim", key.dimension());
                tag.putInt("x", key.x()); tag.putInt("z", key.z());
                out.add(tag);
            }
            return out;
        }

        private static SurveyLedger.ChunkKey maskKey(CompoundTag tag) {
            String dim = tag.getString("dim");
            return dim.isBlank() ? null : new SurveyLedger.ChunkKey(dim, tag.getInt("x"), tag.getInt("z"));
        }

        private static void loadMask(ListTag tags, Set<SurveyLedger.ChunkKey> out) {
            for (int i = 0; i < tags.size(); i++) {
                SurveyLedger.ChunkKey key = maskKey(tags.getCompound(i));
                if (key != null) out.add(key);
            }
        }

        private CompoundTag saveEntry() {
            CompoundTag out = new CompoundTag();
            out.put("state", AtlasState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow());
            out.putString("anchor_dim", anchorDimension);
            out.putInt("anchor_x", anchorX); out.putInt("anchor_z", anchorZ);
            out.putInt("sheet_side_blocks", sheetSideBlocks);
            out.putInt("legacy_sheet_count", legacySheets);
            ListTag sites = new ListTag();
            for (PaperSite site : remoteSheets) {
                CompoundTag point = new CompoundTag();
                point.putInt("x", site.pageX()); point.putInt("z", site.pageZ());
                sites.add(point);
            }
            out.put("remote_paper_sites", sites);
            SurveyLedger.Snapshot masks = ledger.snapshot();
            out.put("survey_mask", saveMask(masks.surveyed()));
            out.put("color_mask", saveMask(masks.colorized()));
            ListTag markersTag = new ListTag();
            for (AtlasMarker marker : markers.values()) markersTag.add(marker.save());
            out.put("markers", markersTag);
            ListTag regionTags = new ListTag();
            for (NamedRegion region : namedRegions.values()) regionTags.add(region.save());
            out.put("named_regions", regionTags);
            return out;
        }

        private static Entry loadEntry(CompoundTag tag) {
            AtlasState state = AtlasState.CODEC.parse(NbtOps.INSTANCE, tag.get("state"))
                    .result().orElse(AtlasState.blank());
            // Persisted atlas sheet width is authoritative. Loading world
            // data must not depend on live ModConfig availability.
            int storedSide = tag.getInt("sheet_side_blocks");
            int width = MapSheetCoverage.valid(storedSide)
                    ? storedSide : MapSheetCoverage.DEFAULT_BLOCKS;
            Entry entry = new Entry(state, width);
            entry.anchorDimension = tag.getString("anchor_dim");
            entry.anchorX = tag.getInt("anchor_x"); entry.anchorZ = tag.getInt("anchor_z");
            entry.legacySheets = tag.contains("legacy_sheet_count", Tag.TAG_INT)
                    ? Math.clamp(tag.getInt("legacy_sheet_count"), 1, state.pages())
                    : state.pages(); // Existing save: preserve ALL original spiral sheets
            ListTag sites = tag.getList("remote_paper_sites", Tag.TAG_COMPOUND);
            for (int i = 0; i < sites.size()
                    && entry.remoteSheets.size() < state.pages() - entry.legacySheets; i++) {
                CompoundTag site = sites.getCompound(i);
                entry.remoteSheets.add(new PaperSite(site.getInt("x"), site.getInt("z")));
            }
            Set<SurveyLedger.ChunkKey> paid = new HashSet<>(), color = new HashSet<>();
            if (tag.contains("survey_mask", Tag.TAG_LIST)) {
                loadMask(tag.getList("survey_mask", Tag.TAG_COMPOUND), paid);
                loadMask(tag.getList("color_mask", Tag.TAG_COMPOUND), color);
            } else {
                // Legacy worlds stored paid coverage alongside a duplicate pixel raster.
                ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag chunk = list.getCompound(i);
                    SurveyLedger.ChunkKey key = maskKey(chunk);
                    if (key == null || chunk.getByteArray("colors").length != 256) continue;
                    paid.add(key);
                    if (chunk.getBoolean("colored")) color.add(key);
                }
            }
            entry.ledger = new SurveyLedger(new SurveyLedger.Snapshot(paid, color));
            ListTag markerTags = tag.getList("markers", Tag.TAG_COMPOUND);
            for (int i = 0; i < markerTags.size(); i++) {
                AtlasMarker marker = AtlasMarker.read(markerTags.getCompound(i));
                if (marker != null) entry.markers.put(marker.id(), marker);
            }
            ListTag regionTags = tag.getList("named_regions", Tag.TAG_COMPOUND);
            for (int i = 0; i < regionTags.size() && i < NamedRegion.MAX_REGIONS; i++) {
                NamedRegion region = NamedRegion.read(regionTags.getCompound(i));
                if (region != null) entry.namedRegions.put(region.id(), region);
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
            // Crafting-origin pages have no remote location: append legacy
            // spiral slots exactly as old versions did.
            saved.legacySheets += item.pages() - saved.state.pages();
            AtlasState s = saved.state;
            saved.state = new AtlasState(s.id(), item.pages(), s.inkUnits(), s.pigmentUnits(), s.glowUnits(),
                    s.redDyes(), s.yellowDyes(), s.blueDyes(),
                    s.inkEnabled(), s.colorEnabled(), s.glowEnabled(), s.compassInstalled(),
                    s.paperUnits(), s.paperEnabled());
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

    public void reveal(Entry entry, SurveyLedger.ChunkKey key, ItemStack item) {
        boolean changed = entry.reveal(key);
        if (changed) {
            item.set(CartographMod.ATLAS_STATE.get(), entry.state());
            setDirty();
        }
    }

    /** Persist a newly paid color bit exactly once. */
    public boolean colorize(Entry entry, SurveyLedger.ChunkKey key, ItemStack item) {
        boolean changed = entry.colorize(key);
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

    public void putRegion(Entry entry, NamedRegion region) {
        entry.putRegion(region);
        setDirty();
    }
    public void deleteRegion(Entry entry, String id) {
        entry.deleteRegion(id);
        setDirty();
    }

    public void anchor(Entry entry, String dimension, int pageX, int pageZ) {
        if (!entry.anchored()) {
            entry.setAnchor(dimension, pageX, pageZ);
            setDirty();
        }
    }
}
