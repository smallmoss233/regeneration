package dev.amble.timelordregen.client;

import dev.amble.lib.animation.AnimatedEntity;
import dev.amble.lib.client.bedrock.BedrockAnimation;
import dev.amble.lib.client.bedrock.BedrockAnimationReference;
import dev.amble.timelordregen.api.RegenerationCapable;
import dev.amble.timelordregen.client.util.ClientParticleUtil;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.AnimationState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

@Environment(EnvType.CLIENT)
public class ClientRegenParticleManager {

    /**
     * 第三人称：每帧在 LivingEntityRenderer.render TAIL 调用
     * 延缓期 + 动画期 都会生成粒子
     */
    public static void trySpawnForEntity(LivingEntity entity, float tickDelta) {
        if (!(entity instanceof RegenerationCapable capable)) return;
        capable.withInfo().ifPresent(info -> {
            if (!info.isActive()) return;
            if (!(entity instanceof AbstractClientPlayerEntity player)) return;

            // ★ 关键：延缓期没有动画，用默认值 0.4f
            float lerpedValue = resolveLerpedValue(entity);

            PlayerEntityRenderer renderer = (PlayerEntityRenderer) MinecraftClient.getInstance()
                    .getEntityRenderDispatcher().getRenderer(player);
            var model = renderer.getModel();
            ClientWorld world = (ClientWorld) entity.getWorld();

            MatrixStack worldStack = buildEntityWorldStack(entity);

            ClientParticleUtil.spawnForPart(world, entity, worldStack, model.rightArm, "right_arm", lerpedValue, false);
            ClientParticleUtil.spawnForPart(world, entity, worldStack, model.leftArm, "left_arm", lerpedValue, false);
            ClientParticleUtil.spawnForPart(world, entity, worldStack, model.head, "head", lerpedValue, false);
        });
    }

    /**
     * 第一人称：每帧在 PlayerEntityRendererMixin.renderArm TAIL 调用
     * 延缓期 + 动画期 都会生成粒子
     */
    public static void trySpawnForFirstPersonArm(AbstractClientPlayerEntity player, ModelPart arm, String partName) {
        if (!(player instanceof RegenerationCapable capable)) return;
        capable.withInfo().ifPresent(info -> {
            if (!info.isActive()) return;

            // ★ 关键：延缓期没有动画，用默认值 0.4f
            float lerpedValue = resolveLerpedValue(player);

            ClientWorld world = (ClientWorld) player.getWorld();
            MatrixStack worldStack = buildEntityWorldStack(player);

            // 第一人称寿命更短，但每帧生成更多
            ClientParticleUtil.spawnForPart(world, player, worldStack, arm, partName, lerpedValue, true);
        });
    }

    /**
     * 构建与 LivingEntityRenderer 1:1 的实体世界矩阵
     */
    private static MatrixStack buildEntityWorldStack(LivingEntity entity) {
        MatrixStack stack = new MatrixStack();
        Vec3d pos = entity.getPos();
        stack.translate(pos.x, pos.y, pos.z);
        stack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - entity.bodyYaw));
        stack.scale(-1.0F, -1.0F, 1.0F);
        stack.translate(0.0F, -1.501F, 0.0F);
        return stack;
    }

    /**
     * ★ 关键：有动画时按动画时间算 lerpedValue，延缓期直接返回 0.4f
     */
    private static float resolveLerpedValue(LivingEntity entity) {
        if (!(entity instanceof AnimatedEntity animated)) return 0.4f;

        BedrockAnimationReference ref = animated.getCurrentAnimation();
        if (ref == null) return 0.4f;

        BedrockAnimation anim = ref.get().orElse(null);
        if (anim == null) return 0.4f;

        AnimationState state = animated.getAnimationState();
        if (state == null) return 0.4f;

        double timeRunning = anim.getRunningSeconds(state);
        if (timeRunning >= anim.animationLength) return 0.4f;

        String animName = anim.name.toLowerCase();
        double animLength = anim.animationLength;
        if (animName.contains("loop")) {
            return 0.4f;
        } else if (animName.contains("end")) {
            return 0f;
        } else {
            float t = Math.max(0f, Math.min(1f, (float) (timeRunning / animLength)));
            return 0.01f + t * 0.3f;
        }
    }
}