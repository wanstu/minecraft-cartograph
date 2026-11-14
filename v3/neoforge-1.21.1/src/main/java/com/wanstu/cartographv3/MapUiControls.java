package com.wanstu.cartographv3;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class MapUiControls {
    private MapUiControls() {}

    record Option<T>(String label, T value) {
        Option {
            Objects.requireNonNull(label);
            Objects.requireNonNull(value);
        }
    }

    abstract static class Control {
        protected int x;
        protected int y;
        protected int width;
        protected int height;
        protected boolean visible = true;
        protected boolean enabled = true;

        Control(int width, int height) {
            this.width = width;
            this.height = height;
        }

        void setBounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        boolean contains(double mouseX, double mouseY) {
            return visible
                    && mouseX >= x && mouseX < x + width
                    && mouseY >= y && mouseY < y + height;
        }

        abstract void render(GuiGraphics graphics, Font font, int mouseX, int mouseY);

        boolean mouseClicked(double mouseX, double mouseY, int button) { return false; }
        boolean mouseDragged(double mouseX, double mouseY, int button,
                             double dragX, double dragY) { return false; }
        boolean mouseReleased(double mouseX, double mouseY, int button) { return false; }
        boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
        boolean charTyped(char codePoint, int modifiers) { return false; }
        void closePopup() {}
        boolean popupOpen() { return false; }
    }


    enum IconType { MENU, CLOSE, SETTINGS, TELEPORT, CHEVRON_RIGHT }

    static final class Icon extends Control {
        private IconType type;
        private int color = 0xffeef2f5;

        Icon(int size, IconType type) {
            super(size, size);
            this.type = Objects.requireNonNull(type);
        }

        void setType(IconType next) {
            type = Objects.requireNonNull(next);
        }

        void setColor(int argb) {
            color = argb;
        }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            int cx = x + width / 2;
            int cy = y + height / 2;
            int s = Math.max(3, Math.min(width, height) / 2 - 1);

            switch (type) {
                case MENU -> {
                    for (int dy : new int[] {-4, 0, 4}) {
                        graphics.fill(cx - s, cy + dy - 1, cx + s + 1, cy + dy + 1, color);
                    }
                }
                case CLOSE -> {
                    for (int i = -s; i <= s; i++) {
                        graphics.fill(cx + i, cy + i, cx + i + 2, cy + i + 2, color);
                        graphics.fill(cx + i, cy - i, cx + i + 2, cy - i + 2, color);
                    }
                }
                case SETTINGS -> {
                    graphics.fill(cx - 1, cy - s, cx + 2, cy + s + 1, color);
                    graphics.fill(cx - s, cy - 1, cx + s + 1, cy + 2, color);
                    graphics.fill(cx - 3, cy - 3, cx + 4, cy + 4, 0xff20252b);
                    graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
                }
                case TELEPORT -> {
                    graphics.fill(cx - 1, cy - s, cx + 2, cy + s + 1, color);
                    graphics.fill(cx - s, cy - 1, cx + s + 1, cy + 2, color);
                    graphics.fill(cx - 3, cy - 3, cx + 4, cy + 4, 0xff20252b);
                }
                case CHEVRON_RIGHT -> {
                    for (int i = -s / 2; i <= s / 2; i++) {
                        int offset = Math.abs(i);
                        graphics.fill(cx + offset - 1, cy + i, cx + offset + 1, cy + i + 2, color);
                    }
                }
            }
        }
    }

    static final class Span extends Control {
        private String text;
        private int color = 0xffedf1f4;
        private boolean centered;

        Span(String text) {
            super(80, 18);
            this.text = text == null ? "" : text;
        }

        void setText(String next) {
            text = next == null ? "" : next;
        }

        void setColor(int argb) {
            color = argb;
        }

        void setCentered(boolean centered) {
            this.centered = centered;
        }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            String shown = text;
            while (font.width(shown) > width && shown.length() > 1) {
                shown = shown.substring(0, shown.length() - 1);
            }
            int tx = centered ? x + Math.max(0, (width - font.width(shown)) / 2) : x;
            int ty = y + Math.max(0, (height - font.lineHeight) / 2);
            graphics.drawString(font, shown, tx, ty, enabled ? color : 0xff7f878e, false);
        }
    }

    static class Paragraph extends Control {
        private String text;
        private int color = 0xffd8dde2;
        private int lineGap = 2;

        Paragraph(int width, int height, String text) {
            super(width, height);
            this.text = text == null ? "" : text;
        }

        void setText(String next) {
            text = next == null ? "" : next;
        }

        void setColor(int argb) {
            color = argb;
        }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            int yy = y;
            for (var line : font.split(net.minecraft.network.chat.Component.literal(text), width)) {
                if (yy + font.lineHeight > y + height) break;
                graphics.drawString(font, line, x, yy, enabled ? color : 0xff7f878e, false);
                yy += font.lineHeight + lineGap;
            }
        }
    }

    static final class P extends Paragraph {
        P(int width, int height, String text) {
            super(width, height, text);
        }
    }

    static final class TextArea extends Control {
        private final int maxLength;
        private final Consumer<String> onChange;
        private String text;
        private boolean focused;

        TextArea(int width, int height, String initial, int maxLength, Consumer<String> onChange) {
            super(width, height);
            this.text = initial == null ? "" : initial;
            this.maxLength = Math.max(1, maxLength);
            this.onChange = onChange == null ? value -> {} : onChange;
        }

        String value() { return text; }

        void setValue(String next) {
            String normalized = next == null ? "" : next;
            text = normalized.substring(0, Math.min(maxLength, normalized.length()));
        }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            graphics.fill(x, y, x + width, y + height, focused ? 0xff657484 : 0xff48515a);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xff252b31);

            int yy = y + 5;
            String[] paragraphs = text.split("\\n", -1);
            for (String paragraph : paragraphs) {
                var lines = font.split(net.minecraft.network.chat.Component.literal(
                        paragraph.isEmpty() ? " " : paragraph), Math.max(1, width - 10));
                for (var line : lines) {
                    if (yy + font.lineHeight > y + height - 4) return;
                    graphics.drawString(font, line, x + 5, yy, 0xffedf1f4, false);
                    yy += font.lineHeight + 2;
                }
            }
            if (focused && (System.currentTimeMillis() / 500L & 1L) == 0L) {
                graphics.fill(x + 5, Math.min(y + height - 7, yy), x + 6,
                        Math.min(y + height - 3, yy + font.lineHeight), 0xfff4f6f8);
            }
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
            focused = enabled && contains(mouseX, mouseY);
            return contains(mouseX, mouseY);
        }

        @Override
        boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!focused || !enabled) return false;
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!text.isEmpty()) {
                    text = text.substring(0, text.length() - 1);
                    onChange.accept(text);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (text.length() < maxLength) {
                    text += "\n";
                    onChange.accept(text);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                focused = false;
                return true;
            }
            return false;
        }

        @Override
        boolean charTyped(char codePoint, int modifiers) {
            if (!focused || !enabled || Character.isISOControl(codePoint)) return false;
            if (text.length() >= maxLength) return true;
            text += codePoint;
            onChange.accept(text);
            return true;
        }
    }

    static final class Table extends Control {
        static final class Row {
            private final List<Control> cells;
            private final Runnable onClick;
            private final int height;
            private final boolean hoverable;

            Row(int height, boolean hoverable, Runnable onClick, Control... cells) {
                this.height = Math.max(1, height);
                this.hoverable = hoverable;
                this.onClick = onClick == null ? () -> {} : onClick;
                this.cells = List.of(cells);
            }
        }

        private final List<Row> rows = new ArrayList<>();
        private int background = 0xee20252b;
        private int hoverBackground = 0xff343b43;
        private int border = 0x00000000;
        private int paddingX = 6;
        private int columnGap = 4;

        Table(int width) {
            super(width, 1);
        }

        Table background(int argb) {
            background = argb;
            return this;
        }

        Table hoverBackground(int argb) {
            hoverBackground = argb;
            return this;
        }

        Table border(int argb) {
            border = argb;
            return this;
        }

        Table padding(int x) {
            paddingX = Math.max(0, x);
            return this;
        }

        Table columnGap(int gap) {
            columnGap = Math.max(0, gap);
            return this;
        }

        void clearRows() {
            rows.clear();
            height = 1;
        }

        void addRow(Row row) {
            rows.add(Objects.requireNonNull(row));
            height = rows.stream().mapToInt(value -> value.height).sum();
        }

        @Override
        void setBounds(int x, int y, int width, int height) {
            super.setBounds(x, y, width, this.height);
            layoutCells();
        }

        void setPosition(int x, int y) {
            this.x = x;
            this.y = y;
            layoutCells();
        }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            int yy = y;
            for (Row row : rows) {
                boolean hover = row.hoverable
                        && mouseX >= x && mouseX < x + width
                        && mouseY >= yy && mouseY < yy + row.height;
                if ((border >>> 24) != 0) {
                    graphics.fill(x, yy, x + width, yy + row.height, border);
                    graphics.fill(x + 1, yy + 1, x + width - 1, yy + row.height - 1,
                            hover ? hoverBackground : background);
                } else {
                    graphics.fill(x, yy, x + width, yy + row.height,
                            hover ? hoverBackground : background);
                }
                for (Control cell : row.cells) {
                    cell.render(graphics, font, mouseX, mouseY);
                }
                yy += row.height;
            }
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !enabled || !visible) return false;
            int yy = y;
            for (Row row : rows) {
                if (mouseX >= x && mouseX < x + width
                        && mouseY >= yy && mouseY < yy + row.height) {
                    for (Control cell : row.cells) {
                        if (cell.mouseClicked(mouseX, mouseY, button)) return true;
                    }
                    row.onClick.run();
                    return true;
                }
                yy += row.height;
            }
            return false;
        }

        @Override
        boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            for (Row row : rows) {
                for (Control cell : row.cells) {
                    if (cell.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
                }
            }
            return false;
        }

        @Override
        boolean mouseReleased(double mouseX, double mouseY, int button) {
            for (Row row : rows) {
                for (Control cell : row.cells) {
                    if (cell.mouseReleased(mouseX, mouseY, button)) return true;
                }
            }
            return false;
        }

        @Override
        boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            for (Row row : rows) {
                for (Control cell : row.cells) {
                    if (cell.keyPressed(keyCode, scanCode, modifiers)) return true;
                }
            }
            return false;
        }

        @Override
        boolean charTyped(char codePoint, int modifiers) {
            for (Row row : rows) {
                for (Control cell : row.cells) {
                    if (cell.charTyped(codePoint, modifiers)) return true;
                }
            }
            return false;
        }

        private void layoutCells() {
            int yy = y;
            for (Row row : rows) {
                int available = width - paddingX * 2 - Math.max(0, row.cells.size() - 1) * columnGap;
                int each = row.cells.isEmpty() ? 0 : Math.max(1, available / row.cells.size());
                int xx = x + paddingX;
                for (int i = 0; i < row.cells.size(); i++) {
                    Control cell = row.cells.get(i);
                    int cellWidth = (i == row.cells.size() - 1)
                            ? x + width - paddingX - xx
                            : each;
                    cell.setBounds(xx, yy, Math.max(1, cellWidth), row.height);
                    xx += cellWidth + columnGap;
                }
                yy += row.height;
            }
        }
    }

    static class TextInput extends Control {
        private final int maxLength;
        private final Consumer<String> onChange;
        private String text;
        private boolean focused;

        TextInput(int width, String initial, int maxLength, Consumer<String> onChange) {
            super(width, 22);
            this.text = initial == null ? "" : initial;
            this.maxLength = Math.max(1, maxLength);
            this.onChange = onChange == null ? value -> {} : onChange;
        }

        String value() {
            return text;
        }

        void setValue(String next) {
            String normalized = next == null ? "" : next;
            if (normalized.length() > maxLength) normalized = normalized.substring(0, maxLength);
            text = normalized;
        }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            int border = focused ? 0xff8da2b5 : 0xff48515a;
            graphics.fill(x, y, x + width, y + height, border);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xff252b31);

            String shown = text;
            while (font.width(shown) > width - 10 && shown.length() > 1) {
                shown = shown.substring(1);
            }
            graphics.drawString(font, shown, x + 5, y + 7, enabled ? 0xffedf1f4 : 0xff858d94, false);

            if (focused && (System.currentTimeMillis() / 500L & 1L) == 0L) {
                int cursorX = Math.min(x + width - 4, x + 5 + font.width(shown));
                graphics.fill(cursorX, y + 5, cursorX + 1, y + height - 5, 0xfff4f6f8);
            }
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
            boolean hit = contains(mouseX, mouseY);
            focused = hit && enabled;
            return hit;
        }

        @Override
        boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!focused || !enabled) return false;
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!text.isEmpty()) {
                    text = text.substring(0, text.length() - 1);
                    onChange.accept(text);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                focused = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                focused = false;
                return true;
            }
            return false;
        }

        @Override
        boolean charTyped(char codePoint, int modifiers) {
            if (!focused || !enabled || Character.isISOControl(codePoint)) return false;
            if (text.length() >= maxLength) return true;
            text += codePoint;
            onChange.accept(text);
            return true;
        }
    }

    static final class Input extends TextInput {
        Input(int width, String initial, int maxLength, Consumer<String> onChange) {
            super(width, initial, maxLength, onChange);
        }
    }

    static final class Select<T> extends Control {
        private final List<Option<T>> options;
        private final Consumer<T> onChange;
        private T value;
        private boolean open;

        Select(int width, List<Option<T>> options, T initial, Consumer<T> onChange) {
            super(width, 22);
            if (options == null || options.isEmpty()) throw new IllegalArgumentException("Select requires options");
            this.options = List.copyOf(options);
            this.value = initial;
            this.onChange = onChange == null ? value -> {} : onChange;
        }

        T value() { return value; }

        void setValue(T next) {
            if (next != null) value = next;
        }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hover = contains(mouseX, mouseY);
            graphics.fill(x, y, x + width, y + height, hover ? 0xff3a424b : 0xff2b3239);
            graphics.drawString(font, labelFor(value), x + 7, y + 7, 0xffffffff, false);
            int cx = x + width - 10;
            graphics.fill(cx - 3, y + 9, cx + 4, y + 10, 0xffdbe1e6);
            graphics.fill(cx - 2, y + 10, cx + 3, y + 11, 0xffdbe1e6);
            graphics.fill(cx - 1, y + 11, cx + 2, y + 12, 0xffdbe1e6);

            if (!open) return;
            int oy = y + height + 2;
            for (int i = 0; i < options.size(); i++) {
                int itemY = oy + i * height;
                boolean itemHover = mouseX >= x && mouseX < x + width
                        && mouseY >= itemY && mouseY < itemY + height;
                graphics.fill(x, itemY, x + width, itemY + height,
                        itemHover ? 0xff3e4751 : 0xf52a3036);
                graphics.drawString(font, options.get(i).label(), x + 7, itemY + 7,
                        Objects.equals(options.get(i).value(), value) ? 0xffffd166 : 0xffedf1f4, false);
            }
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !enabled) return false;
            if (contains(mouseX, mouseY)) {
                open = !open;
                return true;
            }
            if (!open) return false;

            int oy = y + height + 2;
            for (int i = 0; i < options.size(); i++) {
                int itemY = oy + i * height;
                if (mouseX >= x && mouseX < x + width
                        && mouseY >= itemY && mouseY < itemY + height) {
                    value = options.get(i).value();
                    onChange.accept(value);
                    open = false;
                    return true;
                }
            }
            open = false;
            return false;
        }

        @Override void closePopup() { open = false; }
        @Override boolean popupOpen() { return open; }

        private String labelFor(T current) {
            for (Option<T> option : options) {
                if (Objects.equals(option.value(), current)) return option.label();
            }
            return String.valueOf(current);
        }
    }

    static final class Checkbox extends Control {
        private boolean checked;
        private final String label;
        private final Consumer<Boolean> onChange;

        Checkbox(String label, boolean initial, Consumer<Boolean> onChange) {
            super(120, 20);
            this.label = label == null ? "" : label;
            this.checked = initial;
            this.onChange = onChange == null ? value -> {} : onChange;
        }

        boolean value() { return checked; }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            int boxY = y + (height - 14) / 2;
            graphics.fill(x, boxY, x + 14, boxY + 14, 0xff4b555f);
            graphics.fill(x + 1, boxY + 1, x + 13, boxY + 13, 0xff242a30);
            if (checked) {
                graphics.fill(x + 3, boxY + 6, x + 6, boxY + 9, 0xff7dd3a7);
                graphics.fill(x + 5, boxY + 8, x + 11, boxY + 10, 0xff7dd3a7);
                graphics.fill(x + 9, boxY + 4, x + 11, boxY + 9, 0xff7dd3a7);
            }
            graphics.drawString(font, label, x + 20, y + 6, 0xffe3e8ec, false);
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !enabled || !contains(mouseX, mouseY)) return false;
            checked = !checked;
            onChange.accept(checked);
            return true;
        }
    }

    static final class RadioButton extends Control {
        private final String label;
        private final Runnable onSelect;
        private boolean selected;

        RadioButton(String label, boolean selected, Runnable onSelect) {
            super(120, 20);
            this.label = label == null ? "" : label;
            this.selected = selected;
            this.onSelect = onSelect == null ? () -> {} : onSelect;
        }

        boolean selected() { return selected; }
        void setSelected(boolean selected) { this.selected = selected; }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            int cy = y + height / 2;
            graphics.fill(x, cy - 7, x + 14, cy + 7, 0xff4b555f);
            graphics.fill(x + 1, cy - 6, x + 13, cy + 6, 0xff242a30);
            if (selected) graphics.fill(x + 4, cy - 3, x + 10, cy + 3, 0xff7fb4e8);
            graphics.drawString(font, label, x + 20, y + 6, 0xffe3e8ec, false);
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !enabled || !contains(mouseX, mouseY)) return false;
            selected = true;
            onSelect.run();
            return true;
        }
    }

    static final class RadioGroup<T> extends Control {
        private final List<Option<T>> options;
        private final Consumer<T> onChange;
        private T value;

        RadioGroup(List<Option<T>> options, T initial, Consumer<T> onChange) {
            super(150, Math.max(20, options.size() * 20));
            this.options = List.copyOf(options);
            this.value = initial;
            this.onChange = onChange == null ? value -> {} : onChange;
        }

        T value() { return value; }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            for (int i = 0; i < options.size(); i++) {
                int iy = y + i * 20;
                graphics.fill(x, iy + 3, x + 14, iy + 17, 0xff4b555f);
                graphics.fill(x + 1, iy + 4, x + 13, iy + 16, 0xff242a30);
                if (Objects.equals(options.get(i).value(), value)) {
                    graphics.fill(x + 4, iy + 7, x + 10, iy + 13, 0xff7fb4e8);
                }
                graphics.drawString(font, options.get(i).label(), x + 20, iy + 6, 0xffe3e8ec, false);
            }
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !enabled || !contains(mouseX, mouseY)) return false;
            int index = Math.max(0, Math.min(options.size() - 1, ((int) mouseY - y) / 20));
            value = options.get(index).value();
            onChange.accept(value);
            return true;
        }
    }

    static final class Slider extends Control {
        private final int min;
        private final int max;
        private final Consumer<Integer> onChange;
        private int value;
        private boolean dragging;

        Slider(int width, int min, int max, int initial, Consumer<Integer> onChange) {
            super(width, 18);
            if (max <= min) throw new IllegalArgumentException("Invalid slider range");
            this.min = min;
            this.max = max;
            this.value = clamp(initial, min, max);
            this.onChange = onChange == null ? value -> {} : onChange;
        }

        int value() { return value; }
        void setValue(int next) { value = clamp(next, min, max); }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            double ratio = (value - min) / (double) (max - min);
            int knobX = x + (int) Math.round(ratio * width);
            graphics.fill(x, y + 6, x + width, y + 12, 0xff353c43);
            graphics.fill(x, y + 6, knobX, y + 12, 0xff8aa7bf);
            graphics.fill(knobX - 2, y + 3, knobX + 3, y + 15, 0xfff1f3f5);
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !enabled || !contains(mouseX, mouseY)) return false;
            dragging = true;
            update(mouseX);
            return true;
        }

        @Override
        boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (!dragging || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
            update(mouseX);
            return true;
        }

        @Override
        boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (!dragging || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
            dragging = false;
            return true;
        }

        private void update(double mouseX) {
            double ratio = Math.max(0.0, Math.min(1.0, (mouseX - x) / Math.max(1.0, width)));
            int next = min + (int) Math.round(ratio * (max - min));
            if (next != value) {
                value = next;
                onChange.accept(value);
            }
        }
    }

    static final class ColorInput extends Control {
        private static final int PICKER_W = 164;
        private static final int PICKER_H = 116;
        private static final int SV_X = 8;
        private static final int SV_Y = 8;
        private static final int SV_W = 124;
        private static final int SV_H = 80;
        private static final int HUE_X = 140;
        private static final int HUE_W = 14;

        private final Consumer<Integer> onChange;
        private final TextInput hexInput;

        private int rgb;
        private float hue;
        private float saturation;
        private float value;
        private boolean open;
        private int dragMode;

        ColorInput(int width, int initialRgb, Consumer<Integer> onChange) {
            super(width, 22);
            this.onChange = onChange == null ? color -> {} : onChange;
            this.rgb = initialRgb & 0x00ffffff;
            float[] hsv = rgbToHsv(rgb);
            hue = hsv[0];
            saturation = hsv[1];
            value = hsv[2];
            hexInput = new TextInput(Math.max(48, width - 30), hex(rgb), 7, this::acceptHex);
        }

        int value() { return rgb; }

        void setValue(int next) {
            int normalized = next & 0x00ffffff;
            if (normalized == rgb) return;
            rgb = normalized;
            float[] hsv = rgbToHsv(rgb);
            hue = hsv[0];
            saturation = hsv[1];
            value = hsv[2];
            hexInput.setValue(hex(rgb));
        }

        @Override
        void setBounds(int x, int y, int width, int height) {
            super.setBounds(x, y, width, height);
            hexInput.setBounds(x + 28, y, Math.max(48, width - 28), height);
        }

        @Override
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            if (!visible) return;
            graphics.fill(x, y, x + 24, y + height, 0xff48515a);
            graphics.fill(x + 2, y + 2, x + 22, y + height - 2, 0xff000000 | rgb);
            hexInput.render(graphics, font, mouseX, mouseY);
            if (!open) return;

            int px = pickerX();
            int py = y + height + 3;
            graphics.fill(px, py, px + PICKER_W, py + PICKER_H, 0xf522282e);
            graphics.fill(px + 1, py + 1, px + PICKER_W - 1, py + PICKER_H - 1, 0xff1d2227);

            for (int gy = 0; gy < 20; gy++) {
                float v = 1.0f - gy / 19.0f;
                int y0 = py + SV_Y + gy * SV_H / 20;
                int y1 = py + SV_Y + (gy + 1) * SV_H / 20;
                for (int gx = 0; gx < 31; gx++) {
                    float s = gx / 30.0f;
                    int x0 = px + SV_X + gx * SV_W / 31;
                    int x1 = px + SV_X + (gx + 1) * SV_W / 31;
                    graphics.fill(x0, y0, x1, y1, 0xff000000 | hsvToRgb(hue, s, v));
                }
            }

            for (int i = 0; i < 30; i++) {
                float h = i / 30.0f;
                int y0 = py + SV_Y + i * SV_H / 30;
                int y1 = py + SV_Y + (i + 1) * SV_H / 30;
                graphics.fill(px + HUE_X, y0, px + HUE_X + HUE_W, y1,
                        0xff000000 | hsvToRgb(h, 1.0f, 1.0f));
            }

            int markerX = px + SV_X + Math.round(saturation * SV_W);
            int markerY = py + SV_Y + Math.round((1.0f - value) * SV_H);
            graphics.fill(markerX - 2, markerY - 2, markerX + 3, markerY + 3, 0xff000000);
            graphics.fill(markerX - 1, markerY - 1, markerX + 2, markerY + 2, 0xffffffff);

            int hueY = py + SV_Y + Math.round(hue * SV_H);
            graphics.fill(px + HUE_X - 2, hueY - 1, px + HUE_X + HUE_W + 2, hueY + 2, 0xffffffff);

            graphics.fill(px + 8, py + 96, px + 40, py + 108, 0xff000000 | rgb);
            graphics.drawString(font, hex(rgb), px + 48, py + 98, 0xffe8edf1, false);
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !enabled) return false;
            if (hexInput.mouseClicked(mouseX, mouseY, button)) return true;
            if (mouseX >= x && mouseX < x + 24 && mouseY >= y && mouseY < y + height) {
                open = !open;
                return true;
            }
            if (!open) return false;

            int px = pickerX();
            int py = y + height + 3;
            if (inside(mouseX, mouseY, px + SV_X, py + SV_Y, SV_W, SV_H)) {
                dragMode = 1;
                updateSv(mouseX, mouseY);
                return true;
            }
            if (inside(mouseX, mouseY, px + HUE_X, py + SV_Y, HUE_W, SV_H)) {
                dragMode = 2;
                updateHue(mouseY);
                return true;
            }
            if (inside(mouseX, mouseY, px, py, PICKER_W, PICKER_H)) return true;
            open = false;
            return false;
        }

        @Override
        boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || dragMode == 0) return false;
            if (dragMode == 1) updateSv(mouseX, mouseY);
            if (dragMode == 2) updateHue(mouseY);
            return true;
        }

        @Override
        boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || dragMode == 0) return false;
            dragMode = 0;
            return true;
        }

        @Override
        boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return hexInput.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        boolean charTyped(char codePoint, int modifiers) {
            return hexInput.charTyped(codePoint, modifiers);
        }

        @Override
        void closePopup() {
            open = false;
            dragMode = 0;
        }

        @Override boolean popupOpen() { return open; }

        private int pickerX() {
            return x + width - PICKER_W;
        }

        private void updateSv(double mouseX, double mouseY) {
            int px = pickerX();
            int py = y + height + 3;
            saturation = (float) Math.max(0.0, Math.min(1.0, (mouseX - px - SV_X) / SV_W));
            value = 1.0f - (float) Math.max(0.0, Math.min(1.0, (mouseY - py - SV_Y) / SV_H));
            commitHsv();
        }

        private void updateHue(double mouseY) {
            int py = y + height + 3;
            hue = (float) Math.max(0.0, Math.min(0.9999, (mouseY - py - SV_Y) / SV_H));
            commitHsv();
        }

        private void commitHsv() {
            rgb = hsvToRgb(hue, saturation, value);
            hexInput.setValue(hex(rgb));
            onChange.accept(rgb);
        }

        private void acceptHex(String raw) {
            String normalized = raw == null ? "" : raw.trim();
            if (normalized.startsWith("#")) normalized = normalized.substring(1);
            if (normalized.length() != 6) return;
            try {
                int parsed = Integer.parseInt(normalized, 16) & 0x00ffffff;
                rgb = parsed;
                float[] hsv = rgbToHsv(rgb);
                hue = hsv[0];
                saturation = hsv[1];
                value = hsv[2];
                onChange.accept(rgb);
            } catch (NumberFormatException ignored) {}
        }

        private static String hex(int rgb) {
            return String.format("#%06X", rgb & 0x00ffffff);
        }

        private static int hsvToRgb(float h, float s, float v) {
            h = h - (float) Math.floor(h);
            s = Math.max(0.0f, Math.min(1.0f, s));
            v = Math.max(0.0f, Math.min(1.0f, v));

            float c = v * s;
            float hp = h * 6.0f;
            float x = c * (1.0f - Math.abs(hp % 2.0f - 1.0f));
            float r1 = 0, g1 = 0, b1 = 0;
            if (hp < 1) { r1 = c; g1 = x; }
            else if (hp < 2) { r1 = x; g1 = c; }
            else if (hp < 3) { g1 = c; b1 = x; }
            else if (hp < 4) { g1 = x; b1 = c; }
            else if (hp < 5) { r1 = x; b1 = c; }
            else { r1 = c; b1 = x; }

            float m = v - c;
            int r = Math.round((r1 + m) * 255.0f);
            int g = Math.round((g1 + m) * 255.0f);
            int b = Math.round((b1 + m) * 255.0f);
            return (r << 16) | (g << 8) | b;
        }

        private static float[] rgbToHsv(int rgb) {
            float r = ((rgb >>> 16) & 255) / 255.0f;
            float g = ((rgb >>> 8) & 255) / 255.0f;
            float b = (rgb & 255) / 255.0f;
            float max = Math.max(r, Math.max(g, b));
            float min = Math.min(r, Math.min(g, b));
            float d = max - min;

            float h;
            if (d == 0) h = 0;
            else if (max == r) h = ((g - b) / d) % 6.0f;
            else if (max == g) h = (b - r) / d + 2.0f;
            else h = (r - g) / d + 4.0f;
            h /= 6.0f;
            if (h < 0) h += 1.0f;

            float s = max == 0 ? 0 : d / max;
            return new float[] {h, s, max};
        }
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
