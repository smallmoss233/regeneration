package dev.amble.timelordregen.client;

import dev.amble.lib.animation.AnimatedEntity;
import dev.amble.lib.client.bedrock.BedrockAnimation;
import dev.amble.lib.client.bedrock.BedrockAnimationReference;
import dev.amble.timelordregen.api.RegenerationCapable;
import dev.amble.timelordregen.client.particle.RightRegenParticle;
import dev.amble.timelordregen.client.util.ClientParticleUtil;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.AnimationState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Random;

@Environment(EnvType.CLIENT)
public class ClientRegenParticleManager {

    public static void trySpawnForEntity(LivingEntity entity, float tickDelta) {
        if (!(entity instanceof RegenerationCapable capable)) return;
        capable.withInfo().ifPresent(info -> {
            if (!info.isActive()) return;
            if (!(entity instanceof AbstractClientPlayerEntity player)) return;

            boolean isDelay = info.getDelay().isRunning();
            float lerpedValue = resolveLerpedValue(entity);

            PlayerEntityRenderer renderer = (PlayerEntityRenderer) MinecraftClient.getInstance()
                    .getEntityRenderDispatcher().getRenderer(player);
            var model = renderer.getModel();
            ClientWorld world = (ClientWorld) entity.getWorld();

            MatrixStack baseStack = buildEntityWorldStack(entity);

            // 第三人称双手
            ClientParticleUtil.spawnForPart(world, entity, baseStack, model.rightArm, "right_arm", lerpedValue, false, isDelay);
            ClientParticleUtil.spawnForPart(world, entity, baseStack, model.leftArm, "left_arm", lerpedValue, false, isDelay);

            // 第三人称头部（动画期）
            if (!isDelay) {
                ClientParticleUtil.spawnForPart(world, entity, baseStack, model.head, "head", lerpedValue, false, isDelay);
            }
        });
    }

    /**
     * ★ 第一人称：直接在手臂相机空间渲染 billboard 精灵
     */
    public static void tryRenderFirstPersonArm(AbstractClientPlayerEntity player, ModelPart arm, String partName,
                                               MatrixStack matrices, VertexConsumerProvider provider, int light) {
        if (!(player instanceof RegenerationCapable capable)) return;
        capable.withInfo().ifPresent(info -> {
            if (!info.isActive()) return;

            boolean isDelay = info.getDelay().isRunning();

            matrices.push();
            // 应用 arm 局部变换，把原点移到 pivot
            matrices.translate(arm.pivotX / 16.0F, arm.pivotY / 16.0F, arm.pivotZ / 16.0F);
            if (arm.roll != 0.0F) {
                matrices.multiply(RotationAxis.POSITIVE_Z.rotation(arm.roll));
            }
            if (arm.yaw != 0.0F) {
                matrices.multiply(RotationAxis.NEGATIVE_Y.rotation(arm.yaw));
            }
            if (arm.pitch != 0.0F) {
                matrices.multiply(RotationAxis.POSITIVE_X.rotation(arm.pitch));
            }

            // 计算手掌局部偏移（像素单位 → block 单位）
            Vec3d palmOffset = calculatePalmOffset(arm);
            matrices.translate(palmOffset.x / 16.0, palmOffset.y / 16.0, palmOffset.z / 16.0);
            matrices.translate(0, 0, -0.06); // 往内侧埋一点

            // 记录相机空间位置
            Vector4f pos4 = new Vector4f(0, 0, 0, 1);
            matrices.peek().getPositionMatrix().transform(pos4);
            float baseX = pos4.x, baseY = pos4.y, baseZ = pos4.z;
            matrices.pop();

            // ★ 直接在当前矩阵栈渲染 billboard 精灵，不再走 world.addParticle
            Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
            Sprite sprite = RightRegenParticle.Factory.getSpriteProvider().getSprite(player.getRandom());

            int count = isDelay ? 4 : 6;
            float spread = isDelay ? 0.03f : 0.06f;
            float size = isDelay ? 0.05f : 0.1f;
            float alpha = isDelay ? 0.5f : 0.8f;

            for (int i = 0; i < count; i++) {
                double ox = (Math.random() - 0.5) * spread;
                double oy = (Math.random() - 0.5) * spread;
                double oz = (Math.random() - 0.5) * spread;

                matrices.push();
                matrices.translate(baseX + ox, baseY + oy, baseZ + oz);
                // 取消手臂旋转，让精灵始终朝向相机
                matrices.multiply(camera.getRotation());
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));

                renderBillboardQuad(matrices, provider, sprite, size, alpha, light);

                matrices.pop();
            }
        });
    }

    /**
     * 找手臂离 pivot 最远的端面中心（手掌位置）
     */
    private static Vec3d calculatePalmOffset(ModelPart arm) {
        final double[] bestDistSq = {-1.0};
        final float[] palmY = {0};
        final float[] minX = {Float.MAX_VALUE};
        final float[] maxX = {-Float.MAX_VALUE};
        final float[] minZ = {Float.MAX_VALUE};
        final float[] maxZ = {-Float.MAX_VALUE};

        arm.forEachCuboid(new MatrixStack(), (entry, path, index, cuboid) -> {
            double dMin = cuboid.minY * cuboid.minY;
            double dMax = cuboid.maxY * cuboid.maxY;
            if (dMin > bestDistSq[0]) {
                bestDistSq[0] = dMin;
                palmY[0] = cuboid.minY;
            }
            if (dMax > bestDistSq[0]) {
                bestDistSq[0] = dMax;
                palmY[0] = cuboid.maxY;
            }
            if (cuboid.minX < minX[0]) minX[0] = cuboid.minX;
            if (cuboid.maxX > maxX[0]) maxX[0] = cuboid.maxX;
            if (cuboid.minZ < minZ[0]) minZ[0] = cuboid.minZ;
            if (cuboid.maxZ > maxZ[0]) maxZ[0] = cuboid.maxZ;
        });

        if (bestDistSq[0] < 0) return Vec3d.ZERO;
        return new Vec3d((minX[0] + maxX[0]) * 0.5, palmY[0], (minZ[0] + maxZ[0]) * 0.5);
    }

    /**
     * 渲染一个始终朝向相机的半透明四边形
     */
    private static void renderBillboardQuad(MatrixStack matrices, VertexConsumerProvider provider,
                                            Sprite sprite, float size, float alpha, int light) {
        Matrix4f mat = matrices.peek().getPositionMatrix();
        Matrix3f normal = matrices.peek().getNormalMatrix();
        VertexConsumer buffer = provider.getBuffer(RenderLayer.getEntityTranslucent(sprite.getAtlasId()));

        float minU = sprite.getMinU(), maxU = sprite.getMaxU();
        float minV = sprite.getMinV(), maxV = sprite.getMaxV();
        int overlay = OverlayTexture.DEFAULT_UV;

        buffer.vertex(mat, -size, -size, 0).color(1f, 0.9f, 0.5f, alpha).texture(minU, minV).overlay(overlay).light(light).normal(normal, 0, 0, 1).next();
        buffer.vertex(mat, size, -size, 0).color(1f, 0.9f, 0.5f, alpha).texture(maxU, minV).overlay(overlay).light(light).normal(normal, 0, 0, 1).next();
        buffer.vertex(mat, size, size, 0).color(1f, 0.9f, 0.5f, alpha).texture(maxU, maxV).overlay(overlay).light(light).normal(normal, 0, 0, 1).next();
        buffer.vertex(mat, -size, size, 0).color(1f, 0.9f, 0.5f, alpha).texture(minU, maxV).overlay(overlay).light(light).normal(normal, 0, 0, 1).next();
    }

    private static MatrixStack buildEntityWorldStack(LivingEntity entity) {
        MatrixStack stack = new MatrixStack();
        Vec3d pos = entity.getPos();
        stack.translate(pos.x, pos.y, pos.z);
        stack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - entity.bodyYaw));
        stack.scale(-1.0F, -1.0F, 1.0F);
        stack.translate(0.0F, -1.5F, 0.0F);
        return stack;
    }

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