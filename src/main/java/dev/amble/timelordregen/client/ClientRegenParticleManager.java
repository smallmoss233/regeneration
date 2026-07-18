package dev.amble.timelordregen.client;

import dev.amble.lib.animation.AnimatedEntity;
import dev.amble.lib.client.bedrock.BedrockAnimation;
import dev.amble.lib.client.bedrock.BedrockAnimationReference;
import dev.amble.timelordregen.api.RegenerationCapable;
import dev.amble.timelordregen.client.particle.RightRegenParticle;
import dev.amble.timelordregen.client.util.ClientParticleUtil;
import dev.amble.timelordregen.core.particle_effects.RegenParticleEffect;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.AnimationState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

            ClientParticleUtil.spawnForPart(world, entity, baseStack, model.rightArm, "right_arm", lerpedValue, false, isDelay);
            ClientParticleUtil.spawnForPart(world, entity, baseStack, model.leftArm, "left_arm", lerpedValue, false, isDelay);

            if (!isDelay) {
                ClientParticleUtil.spawnForPart(world, entity, baseStack, model.head, "head", lerpedValue, false, isDelay);
            }
        });
    }

    /**
     * 第一人称手臂粒子 —— 接入真实粒子系统
     * 核心修正：用手动构建的视图→世界旋转，替代有歧义的 camera.getRotation()
     */
    public static void tryRenderFirstPersonArm(AbstractClientPlayerEntity player, ModelPart arm, String partName,
                                               MatrixStack matrices, VertexConsumerProvider provider, int light) {
        if (!(player instanceof RegenerationCapable capable)) return;

        capable.withInfo().ifPresent(info -> {
            if (!info.isActive()) return;

            boolean isDelay = info.getDelay().isRunning();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientWorld world = (ClientWorld) player.getWorld();
            float lerpedValue = resolveLerpedValue(player);

            // 1. 应用手臂变换，提取手掌【视图空间】坐标
            matrices.push();
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

            Vec3d palmOffset = calculatePalmOffset(arm);
            matrices.translate(palmOffset.x / 16.0, palmOffset.y / 16.0, palmOffset.z / 16.0);
            matrices.translate(0, 0, -0.06);

            Matrix4f mat = matrices.peek().getPositionMatrix();
            float vx = mat.m30(), vy = mat.m31(), vz = mat.m32();
            matrices.pop();

            // ================================================================
            // 2. 视图空间 → 世界空间
            //    MC 视图矩阵 ≈ Rx(pitch) * Ry(headYaw + 180)
            //    所以逆旋转 = Ry(-headYaw - 180) * Rx(-pitch)
            //    不依赖 camera.getRotation()，完全手搓，可控
            // ================================================================
            Quaternionf viewToWorld = RotationAxis.POSITIVE_Y.rotationDegrees(-player.headYaw - 180);
            viewToWorld.mul(RotationAxis.POSITIVE_X.rotationDegrees(-player.getPitch()));

            Vector3f worldOffset = new Vector3f(vx, vy, vz);
            viewToWorld.transform(worldOffset);

            Vec3d cameraPos = client.gameRenderer.getCamera().getPos();
            double palmWx = cameraPos.x + worldOffset.x;
            double palmWy = cameraPos.y + worldOffset.y;
            double palmWz = cameraPos.z + worldOffset.z;

            // 手掌发射方向（视图空间手臂指向 -Z，转回世界空间）
            Vector3f palmDirView = new Vector3f(0, 0, -1);
            viewToWorld.transform(palmDirView);
            Vec3d palmDir = new Vec3d(palmDirView.x, palmDirView.y, palmDirView.z).normalize();

            // ================================================================
            // 3. 每帧生成真实粒子，0 速度，严格跟随手掌
            //    第三人称延缓期每 tick 生成 3 个，第一人称 60fps 下每帧生成 1 个，
            //    总密度和第三人称一致，且生成点实时跟随手掌移动
            // ================================================================
            long seedBase = player.getId() ^ partName.hashCode() ^ 0xDEADBEEFL;

            if (isDelay) {
                // 延缓期：1 个/帧，速度为 0，随机散布在手掌附近
                Random rand = new Random(seedBase + player.age * 31 + (int)(client.getTickDelta() * 100));
                double ox = (rand.nextDouble() - 0.5) * 0.08;
                double oy = (rand.nextDouble() - 0.5) * 0.08;
                double oz = (rand.nextDouble() - 0.5) * 0.08;

                world.addParticle(
                        new RegenParticleEffect(player.getId(), 0, 0, true, false, lerpedValue, false),
                        palmWx + ox, palmWy + oy, palmWz + oz,
                        0.0, 0.0, 0.0
                );
            } else {
                // 动画期：每帧 2 个，带速度，拉出拖尾
                int count = 2;
                double spreadAngle = Math.toRadians(32.0);

                Vec3d worldUp = Math.abs(palmDir.y) < 0.99 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
                Vec3d axisX = palmDir.crossProduct(worldUp).normalize();
                Vec3d axisY = palmDir.crossProduct(axisX).normalize();

                Random rand = new Random(seedBase + player.age * 17 + (int)(client.getTickDelta() * 100));

                for (int i = 0; i < count; i++) {
                    double speed = 1.0 + rand.nextDouble() * 0.6;

                    double theta = rand.nextDouble() * 2.0 * Math.PI;
                    double phi = rand.nextDouble() * spreadAngle;
                    double sinPhi = Math.sin(phi), cosPhi = Math.cos(phi);
                    double cosTheta = Math.cos(theta), sinTheta = Math.sin(theta);

                    Vec3d coneDir = palmDir.multiply(cosPhi)
                            .add(axisX.multiply(cosTheta * sinPhi))
                            .add(axisY.multiply(sinTheta * sinPhi));

                    world.addParticle(
                            new RegenParticleEffect(player.getId(), 0, 0, true, false, lerpedValue, false),
                            palmWx, palmWy, palmWz,
                            coneDir.x * speed, coneDir.y * speed, coneDir.z * speed
                    );
                }
            }
        });
    }

    /**
     * 找手臂离原点最远的端面中心（手掌位置）
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