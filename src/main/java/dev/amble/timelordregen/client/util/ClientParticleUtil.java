package dev.amble.timelordregen.client.util;

import dev.amble.timelordregen.core.particle_effects.RegenParticleEffect;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class ClientParticleUtil {

    public static void spawnForPart(ClientWorld world, LivingEntity entity,
                                    MatrixStack worldStack, ModelPart part,
                                    String partName, float lerpedValue, boolean shortLife) {

        worldStack.push();

        // 手动应用部位变换（与 ModelPart.rotate 完全一致）
        worldStack.translate(part.pivotX / 16.0F, part.pivotY / 16.0F, part.pivotZ / 16.0F);
        if (part.roll != 0.0F) {
            worldStack.multiply(RotationAxis.POSITIVE_Z.rotation(part.roll));
        }
        if (part.yaw != 0.0F) {
            worldStack.multiply(RotationAxis.NEGATIVE_Y.rotation(part.yaw));
        }
        if (part.pitch != 0.0F) {
            worldStack.multiply(RotationAxis.POSITIVE_X.rotation(part.pitch));
        }

        // 计算发射点（局部空间，相对于 ModelPart 局部原点）
        Vec3d emitLocal = calculateEmitPoint(part, partName);
        Vec3d emitRelPivot = emitLocal.subtract(part.pivotX, part.pivotY, part.pivotZ);
        worldStack.translate(emitRelPivot.x / 16.0, emitRelPivot.y / 16.0, emitRelPivot.z / 16.0);

        // 读取世界坐标
        Vector4f worldPos = new Vector4f(0, 0, 0, 1);
        worldStack.peek().getPositionMatrix().transform(worldPos);

        // ★ 修正：方向用局部固定前向向量，经 normalMatrix 跟随旋转
        Vec3d dirLocal = calculateDirection(partName);
        Vector3f direction = new Vector3f((float) dirLocal.x, (float) dirLocal.y, (float) dirLocal.z);
        worldStack.peek().getNormalMatrix().transform(direction);
        if (direction.length() > 0.001f) {
            direction.normalize();
        } else {
            direction.set(0, 1, 0);
        }

        worldStack.pop();

        // 第一人称多生成几个弥补短寿命
        int count = shortLife ? 3 : 1;
        for (int i = 0; i < count; i++) {
            double speed = 0.3 + Math.random() * 0.2;
            double vx = direction.x * speed + (Math.random() - 0.5) * 0.1;
            double vy = direction.y * speed + (Math.random() - 0.5) * 0.1;
            double vz = direction.z * speed + (Math.random() - 0.5) * 0.1;

            world.addParticle(
                    new RegenParticleEffect(entity.getId(), 0, 0, true, false, lerpedValue, shortLife),
                    worldPos.x, worldPos.y, worldPos.z,
                    vx, vy, vz
            );
        }
    }

    private static Vec3d calculateEmitPoint(ModelPart part, String partName) {
        final double[] avgX = {0};
        final double[] avgZ = {0};
        final int[] count = {0};

        if ("head".equals(partName)) {
            final double[] maxY = {Double.MIN_VALUE};
            part.forEachCuboid(new MatrixStack(), (entry, path, index, cuboid) -> {
                maxY[0] = Math.max(maxY[0], cuboid.maxY);
                avgX[0] += (cuboid.minX + cuboid.maxX) * 0.5;
                avgZ[0] += (cuboid.minZ + cuboid.maxZ) * 0.5;
                count[0]++;
            });
            if (count[0] == 0) return Vec3d.ZERO;
            return new Vec3d(avgX[0] / count[0], maxY[0], avgZ[0] / count[0]);
        } else {
            final double[] minY = {Double.MAX_VALUE};
            part.forEachCuboid(new MatrixStack(), (entry, path, index, cuboid) -> {
                minY[0] = Math.min(minY[0], cuboid.minY);
                avgX[0] += (cuboid.minX + cuboid.maxX) * 0.5;
                avgZ[0] += (cuboid.minZ + cuboid.maxZ) * 0.5;
                count[0]++;
            });
            if (count[0] == 0) return Vec3d.ZERO;
            return new Vec3d(avgX[0] / count[0], minY[0], avgZ[0] / count[0]);
        }
    }

    /**
     * ★ 修正：手臂用局部 Z 轴作为前向，头部用 Y- 轴（翻转后向上）
     * 如果还是反的，把手臂的 (0, 0, 1) 改成 (0, 0, -1) 即可
     */
    private static Vec3d calculateDirection(String partName) {
        if ("head".equals(partName)) {
            // 头部：局部 Y- 经 scale(-1,-1,1) 的 normalMatrix 翻转后 = 世界 Y+
            return new Vec3d(0, -1, 0);
        } else {
            // 手臂：局部 Z+ 为手掌前向（跟随手臂旋转）
            // 如果实际朝反了，改成 (0, 0, -1)
            return new Vec3d(0, 0, 1);
        }
    }
}