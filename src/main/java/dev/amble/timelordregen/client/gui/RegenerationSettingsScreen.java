package dev.amble.timelordregen.client.gui;

import dev.amble.timelordregen.api.RegenerationInfo;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.joml.Vector3f;

public class RegenerationSettingsScreen extends Screen {
    private final PlayerEntity player;
    private final RegenerationInfo info;
    private ColorSlider redSlider;
    private ColorSlider greenSlider;
    private ColorSlider blueSlider;
    private Vector3f color;

    public RegenerationSettingsScreen(PlayerEntity player) {
        super(Text.translatable("gui.regen.settings.title"));
        this.player = player;
        this.info = RegenerationInfo.get(player);
        // 从 info 读取颜色，若未设置则使用默认 (1.0, 1.0, 1.0) 白色
        Vector3f loaded = info.getParticleColor();
        this.color = loaded != null ? loaded : new Vector3f(1.0f, 1.0f, 1.0f);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        redSlider = new ColorSlider(centerX - 100, centerY - 40, 200, 20, Text.literal("Red"), color.x(), this::onColorChange);
        greenSlider = new ColorSlider(centerX - 100, centerY - 10, 200, 20, Text.literal("Green"), color.y(), this::onColorChange);
        blueSlider = new ColorSlider(centerX - 100, centerY + 20, 200, 20, Text.literal("Blue"), color.z(), this::onColorChange);

        this.addDrawableChild(redSlider);
        this.addDrawableChild(greenSlider);
        this.addDrawableChild(blueSlider);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Save & Close"), button -> this.saveAndClose())
                .position(centerX - 50, centerY + 60)
                .size(100, 20)
                .build()
        );
    }

    private void onColorChange(float value) {
        // 更新本地颜色对象
        color.set(redSlider.getSliderValue(), greenSlider.getSliderValue(), blueSlider.getSliderValue());
        // 保存到 RegenerationInfo（立即持久化）
        info.setParticleColor(color);
        // TODO: 如果需要通知服务端同步，在这里发送网络包
    }

    private void saveAndClose() {
        // 确保最后一次拖动也保存
        onColorChange(0);
        this.close();
    }

    // 内部滑块类
    private class ColorSlider extends SliderWidget {
        private final java.util.function.Consumer<Float> onChange;
        private final String label;

        public ColorSlider(int x, int y, int width, int height, Text label, double value, java.util.function.Consumer<Float> onChange) {
            super(x, y, width, height, label, value);
            this.onChange = onChange;
            this.label = label.getString();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal(label + ": " + (int) (this.value * 255)));
        }

        @Override
        protected void applyValue() {
            onChange.accept((float) this.value);
        }

        public float getSliderValue() {
            return (float) this.value;
        }
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        this.renderBackground(drawContext);
        // 显示剩余重生次数
        drawContext.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("gui.regen.settings.remaining", info.getUsesLeft()),
                this.width / 2, this.height / 2 - 70, 0xFFFFFF);
        super.render(drawContext, mouseX, mouseY, delta);
    }
}