package dev.amble.timelordregen.client.util;

import dev.amble.timelordregen.core.particle_effects.RegenParticleEffect;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector4f;

public class ClientParticleUtil {

    /**
     * ★ 关键修正：不再手动拼矩阵，而是让 forEachCuboid 带着 baseStack 自己算世界坐标。
     * 这样能正确处理 Bedrock 子骨骼、层级 pivot 和动画旋转。
     */
    public static void spawnForPart(ClientWorld world, LivingEntity entity,
                                    MatrixStack baseStack, ModelPart part,
                                    String partName, float lerpedValue, boolean shortLife,
                                    boolean isDelay) {

        // part pivot 在模型根空间中的坐标（block 单位）
        float pivotX = part.pivotX / 16.0F;
        float pivotY = part.pivotY / 16.0F;
        float pivotZ = part.pivotZ / 16.0F;

        // 计算 part pivot 的世界坐标
        Vector4f pivotWorld4 = new Vector4f(pivotX, pivotY, pivotZ, 1.0F);
        baseStack.peek().getPositionMatrix().transform(pivotWorld4);
        Vec3d pivotWorld = new Vec3d(pivotWorld4.x, pivotWorld4.y, pivotWorld4.z);

        // 遍历所有 cuboid（含子部件），找离 pivot 最远的顶点
        // forEachCuboid 内部会自动应用 part 及其子部件的 pivot + 旋转（完全跟随动画）
        final Vec3d[] bestPos = {null};
        final double[] bestDistSq = {-1.0};

        part.forEachCuboid(baseStack, (entry, path, index, cuboid) -> {
            float minX = cuboid.minX / 16.0F;
            float minY = cuboid.minY / 16.0F;
            float minZ = cuboid.minZ / 16.0F;
            float maxX = cuboid.maxX / 16.0F;
            float maxY = cuboid.maxY / 16.0F;
            float maxZ = cuboid.maxZ / 16.0F;

            float[][] corners = {
                    {minX, minY, minZ}, {minX, minY, maxZ}, {minX, maxY, minZ}, {minX, maxY, maxZ},
                    {maxX, minY, minZ}, {maxX, minY, maxZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}
            };

            for (float[] c : corners) {
                Vector4f v = new Vector4f(c[0], c[1], c[2], 1.0F);
                entry.getPositionMatrix().transform(v);
                Vec3d worldPos = new Vec3d(v.x, v.y, v.z);
                double distSq = worldPos.squaredDistanceTo(pivotWorld);
                if (distSq > bestDistSq[0]) {
                    bestDistSq[0] = distSq;
                    bestPos[0] = worldPos;
                }
            }
        });

        if (bestPos[0] == null) return;

        Vec3d emitWorldPos = bestPos[0];
        Vec3d direction = emitWorldPos.subtract(pivotWorld);
        if (direction.lengthSquared() > 0.001) {
            direction = direction.normalize();
        } else {
            direction = new Vec3d(0, 1, 0);
        }

        int count = shortLife ? 3 : 1;
        for (int i = 0; i < count; i++) {
            double vx, vy, vz;

            if (isDelay) {
                // 延缓期：不喷射，只在原地轻微漂移（手掌位置聚集）
                vx = (Math.random() - 0.5) * 0.02;
                vy = (Math.random() - 0.5) * 0.02;
                vz = (Math.random() - 0.5) * 0.02;
            } else {
                // 动画期：沿手臂/头部轴向向外喷射
                double speed = 0.3 + Math.random() * 0.2;
                vx = direction.x * speed + (Math.random() - 0.5) * 0.1;
                vy = direction.y * speed + (Math.random() - 0.5) * 0.1;
                vz = direction.z * speed + (Math.random() - 0.5) * 0.1;
            }

            world.addParticle(
                    new RegenParticleEffect(entity.getId(), 0, 0, true, false, lerpedValue, shortLife),
                    emitWorldPos.x, emitWorldPos.y, emitWorldPos.z,
                    vx, vy, vz
            );
        }
    }
}