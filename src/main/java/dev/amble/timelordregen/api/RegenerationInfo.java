package dev.amble.timelordregen.api;

import dev.amble.lib.client.bedrock.BedrockAnimationReference;
import dev.amble.timelordregen.RegenerationMod;
import dev.amble.timelordregen.animation.AnimationSet;
import dev.amble.timelordregen.animation.AnimationTemplate;
import dev.amble.timelordregen.animation.RegenAnimRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.amble.lib.animation.AnimatedEntity;
import dev.amble.lib.animation.AnimationTracker;
import dev.amble.timelordregen.data.Attachments;
import dev.drtheo.scheduler.api.TimeUnit;
import dev.drtheo.scheduler.api.common.Scheduler;
import dev.drtheo.scheduler.api.common.TaskStage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RegenerationInfo {
    public static final Identifier SYNC_PACKET = RegenerationMod.id("sync_info");

    // ========== 静态初始化事件监听 ==========
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            List<Entity> entities = new ArrayList<>(server.getPlayerManager().getPlayerList());
            server.getWorlds().forEach(world -> world.iterateEntities().forEach(entities::add));
            entities.forEach(entity -> {
                if (!(entity instanceof RegenerationCapable regen) || !(entity instanceof LivingEntity living)) return;
                RegenerationInfo info = regen.getRegenerationInfo();
                if (info != null && info.isRegenerating()) {
                    info.finish(living);
                }
            });
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity entity = handler.getPlayer();
            if (!(entity instanceof RegenerationCapable regen)) return;
            RegenerationInfo info = regen.getRegenerationInfo();
            if (info != null && info.isRegenerating()) {
                info.setRegenQueued(true);
                info.stopRegeneration(entity);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity entity = handler.getPlayer();
            if (!(entity instanceof RegenerationCapable regen)) return;
            RegenerationInfo info = regen.getRegenerationInfo();
            if (info != null && info.isRegenQueued()) {
                if (!info.start(entity)) {
                    info.setRegenQueued(false);
                    info.markDirty();
                }
            }
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
            RegenerationInfo info = RegenerationInfo.get(entity);
            if (info == null) return true;
            if (!info.isActive() && info.getUsesLeft() > 0) {
                return !info.tryStart(entity);
            }
            return true;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            RegenerationInfo info = RegenerationInfo.get(player);
            if (info != null && info.getDelay().hasEvent()) {
                if (!world.getBlockState(pos).isIn(BlockTags.SNOW)) return ActionResult.PASS;
                info.tryStopDelayEvent(player);
                world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, player.getSoundCategory(), 0.25F, 1.0F);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
    }

    // ========== CODEC（序列化）==========
    public static final Codec<RegenerationInfo> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("usesLeft").forGetter(RegenerationInfo::getUsesLeft),
            Codec.BOOL.fieldOf("isRegenerating").forGetter(RegenerationInfo::isRegenerating),
            Codec.BOOL.fieldOf("regenQueued").forGetter(RegenerationInfo::isRegenQueued),
            Identifier.CODEC.fieldOf("animation").forGetter(RegenerationInfo::getAnimationId),
            Delay.CODEC.fieldOf("delay").forGetter(info -> info.delay),
            Codec.FLOAT.fieldOf("colorR").forGetter(info -> info.particleColor.x()),
            Codec.FLOAT.fieldOf("colorG").forGetter(info -> info.particleColor.y()),
            Codec.FLOAT.fieldOf("colorB").forGetter(info -> info.particleColor.z())
    ).apply(instance, (usesLeft, isRegenerating, regenQueued, animationId, delay, r, g, b) -> {
        RegenerationInfo info = new RegenerationInfo(usesLeft, isRegenerating, regenQueued, animationId, delay);
        info.particleColor.set(r, g, b);
        return info;
    }));

    public static final int MAX_REGENERATIONS = 12;

    // ========== 字段 ==========
    private int usesLeft;
    private boolean isRegenerating;
    private boolean regenQueued;
    private AnimationTemplate animation;
    private final Delay delay;
    private boolean dirty;
    private final Vector3f particleColor;
    @Nullable
    private AnimationSet currentAnimationSet;

    // ========== 构造器 ==========
    private RegenerationInfo(int usesLeft, boolean isRegenerating, boolean regenQueued, Identifier animation, Delay delay) {
        this.usesLeft = usesLeft;
        this.isRegenerating = isRegenerating;
        this.regenQueued = regenQueued;
        this.animation = RegenAnimRegistry.getInstance().getOrFallback(animation);
        this.delay = delay;
        this.particleColor = new Vector3f(1.0f, 1.0f, 1.0f);
    }

    public RegenerationInfo() {
        this(0, false, false, RegenAnimRegistry.getInstance().getRandom().id(), new Delay());
    }

    // ========== Getter / Setter ==========
    public int getUsesLeft() {
        return usesLeft;
    }

    public void setUsesLeft(int usesLeft) {
        this.usesLeft = MathHelper.clamp(usesLeft, 0, MAX_REGENERATIONS);
        this.markDirty();
    }

    public boolean isRegenerating() {
        return isRegenerating;
    }

    public void setRegenerating(boolean regenerating) {
        isRegenerating = regenerating;
        this.markDirty();
    }

    public boolean isRegenQueued() {
        return regenQueued;
    }

    public void setRegenQueued(boolean regenQueued) {
        this.regenQueued = regenQueued;
        this.markDirty();
    }

    public AnimationTemplate getAnimation() {
        if (this.animation == null) {
            this.animation = RegenAnimRegistry.getInstance().getRandom();
        }
        return animation;
    }

    public void setAnimation(AnimationTemplate animation) {
        this.animation = animation;
        this.markDirty();
    }

    public Delay getDelay() {
        return delay;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public Vector3f getParticleColor() {
        return particleColor;
    }

    public void setParticleColor(Vector3f color) {
        this.particleColor.set(color);
        this.markDirty();
    }

    // ========== 业务方法 ==========
    public void decrement() {
        this.setUsesLeft(this.getUsesLeft() - 1);
    }

    /**
     * 每 tick 调用，处理延迟和重生状态
     */
    public void tick(LivingEntity entity) {
        if (entity.getWorld().isClient) return;

        if (this.isDirty()) {
            this.setDirty(false);
            for (ServerPlayerEntity player : entity.getWorld().getServer().getPlayerManager().getPlayerList()) {
                this.sync(player, entity.getUuid());
            }
        }

        if (delay.isRunning()) {
            if (this.getUsesLeft() <= 0) {
                delay.stop();
                this.markDirty();
                return;
            }
            Delay.Result result = delay.tick(entity.age);
            switch (result) {
                case REGENERATE -> {
                    this.setRegenQueued(true);
                    delay.stop();
                    this.markDirty();
                }
                case EVENT -> {
                    RegenerationEvents.DELAY_EVENT.invoker().onEvent(entity, this);
                    this.markDirty();
                }
                case NONE -> {}
            }
        }

        if (isRegenQueued()) {
            if (!this.start(entity)) {
                this.setRegenQueued(false);
                this.markDirty();
                RegenerationMod.LOGGER.warn("Regeneration start failed for {}, clearing queued state", entity.getUuid());
            }
        }
    }

    /**
     * 尝试触发重生延迟（如果条件允许）
     */
    public boolean tryStart(LivingEntity entity) {
        if (this.isActive() || this.usesLeft <= 0) return false;
        if (!entity.isAlive()) return false;
        this.delay.start(entity.age);
        this.markDirty();
        entity.setHealth(entity.getMaxHealth());
        if (entity instanceof AnimatedEntity animated) {
            animated.playAnimation(BedrockAnimationReference.parse(Identifier.of("start", RegenerationMod.RANDOM.nextBoolean() ? "right" : "left")));
        }
        RegenerationMod.LOGGER.info("Delay started for {}, will regenerate after {} ticks", entity.getUuid(), Delay.MAX_DURATION);
        return true;
    }

    /**
     * 实际开始重生过程
     */
    private boolean start(LivingEntity entity) {
        if (this.isRegenerating() || this.usesLeft <= 0) return false;
        if (!entity.isAlive()) return false;

        this.setRegenQueued(false);
        this.decrement();
        this.setRegenerating(true);
        entity.setHealth(entity.getMaxHealth());

        if (entity instanceof AnimatedEntity animated) {
            AnimationTemplate template = RegenAnimRegistry.getInstance().getRandom();
            AnimationSet set = template.instantiate(true);
            this.currentAnimationSet = set;

            set.finish(() -> {
                RegenerationMod.LOGGER.info("Animation finish callback for {}", entity.getUuid());
                this.finish(entity);
            });
            set.start(animated);
            for (AnimationTemplate.Stage stage : AnimationTemplate.Stage.values()) {
                set.callback(stage, s -> {
                    RegenerationEvents.CHANGE_STAGE.invoker().onStateChange(entity, this, s);
                });
            }
            RegenerationMod.LOGGER.info("Started regeneration animation for {}", entity.getUuid());
        } else {
            Scheduler.get().runTaskLater(() -> {
                RegenerationMod.LOGGER.info("Non-animated entity regeneration finish for {}", entity.getUuid());
                this.finish(entity);
            }, TaskStage.END_SERVER_TICK, TimeUnit.SECONDS, 5);
        }

        RegenerationEvents.START.invoker().onStart(entity, this);
        this.markDirty();
        return true;
    }

    /**
     * 完成重生过程
     */
    private void finish(LivingEntity entity) {
        RegenerationMod.LOGGER.info("finish() called for {}", entity.getUuid());

        // FIX: 强制清理动画状态，防止玩家卡在动画中无法移动
        this.resetAnimationState(entity);

        this.stopRegeneration(entity);
        RegenerationEvents.FINISH.invoker().onFinish(entity, this);
        this.setAnimation(RegenAnimRegistry.getInstance().getRandom());
        this.markDirty();

        entity.setNoGravity(false);
        entity.setVelocity(entity.getVelocity().multiply(0.5));
        entity.updatePosition(entity.getX(), entity.getY(), entity.getZ());
        RegenerationMod.LOGGER.info("Regeneration finished for {}", entity.getUuid());
    }

    /**
     * 重置实体的动画状态，防止动画残留导致无法移动
     */
    private void resetAnimationState(LivingEntity entity) {
        if (!(entity instanceof AnimatedEntity animated)) return;

        try {
            animated.getAnimationState().stop();
            AnimationTracker.getInstance().remove(animated.getUuid());
            RegenerationMod.LOGGER.debug("Animation state reset for {}", entity.getUuid());
        } catch (Exception e) {
            RegenerationMod.LOGGER.error("Failed to reset animation state for {}", entity.getUuid(), e);
        }

        this.currentAnimationSet = null;
    }

    private Identifier getAnimationId() {
        return this.getAnimation().id();
    }

    /**
     * 停止重生过程并清理所有相关状态
     * @param entity 需要清理动画状态的实体，可为 null（仅在确定无动画状态时）
     */
    public void stopRegeneration(@Nullable LivingEntity entity) {
        // FIX: 如果有正在运行的动画集，先取消它
        if (this.currentAnimationSet != null) {
            this.currentAnimationSet.cancel();
            this.currentAnimationSet = null;
        }

        // FIX: 清理实体动画状态
        if (entity instanceof AnimatedEntity) {
            this.resetAnimationState(entity);
        }

        this.setRegenerating(false);
        this.delay.stop();
        this.markDirty();
        RegenerationMod.LOGGER.debug("Regeneration stopped for (state reset)");
    }

    /**
     * @deprecated 请使用 {@link #stopRegeneration(LivingEntity)} 以确保动画状态被正确清理
     */
    @Deprecated
    public void stopRegeneration() {
        this.stopRegeneration(null);
    }

    public boolean tryStopDelayEvent(@Nullable LivingEntity entity) {
        if (!this.delay.hasEvent()) return false;
        this.delay.stopEvent();
        this.markDirty();
        RegenerationEvents.DELAY_FURTHER.invoker().onEvent(entity, this);
        return true;
    }

    public boolean isActive() {
        return this.isRegenerating() || this.delay.isRunning() || this.isRegenQueued();
    }

    public void markDirty() {
        this.setDirty(true);
    }

    private void sync(ServerPlayerEntity target, UUID sourceId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(sourceId);
        buf.encodeAsJson(CODEC, this);
        ServerPlayNetworking.send(target, SYNC_PACKET, buf);
    }

    @Environment(EnvType.CLIENT)
    public static void receive(PacketByteBuf buf) {
        UUID playerId = buf.readUuid();
        RegenerationInfo info = buf.decodeAsJson(CODEC);
        if (info == null) {
            RegenerationMod.LOGGER.warn("Received null RegenerationInfo from server for player {}", playerId);
            return;
        }
        if (net.minecraft.client.MinecraftClient.getInstance().world == null) {
            RegenerationMod.LOGGER.warn("Received RegenerationInfo from server for player {}, but client world is null", playerId);
            return;
        }
        PlayerEntity entity = net.minecraft.client.MinecraftClient.getInstance().world.getPlayerByUuid(playerId);
        if (entity == null) {
            RegenerationMod.LOGGER.warn("Received RegenerationInfo from server for player {}, but could not find player in client world", playerId);
            return;
        }
        if (!(entity instanceof RegenerationCapable)) {
            RegenerationMod.LOGGER.warn("Received RegenerationInfo from server for player {}, but player is not RegenerationCapable", playerId);
            return;
        }
        entity.setAttached(Attachments.REGENERATION, info);
        RegenerationMod.LOGGER.debug("RegenerationInfo synced to client for {}", playerId);
    }

    public static RegenerationInfo get(LivingEntity entity) {
        if (!(entity instanceof RegenerationCapable capability)) return null;
        return capability.getRegenerationInfo();
    }

    // ========== 内部类 Delay ==========
    public static class Delay {
        public static final Codec<Delay> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("start").forGetter(delay -> delay.start),
                Codec.INT.fieldOf("lastEvent").forGetter(delay -> delay.lastEvent)
        ).apply(instance, Delay::new));

        private static final int MAX_DURATION = 6000; // 5 minutes
        private static final int TIME_TO_STOP = 300;  // 5 seconds
        private static final float EVENT_CHANCE = 0.05f;

        private int start;
        private int lastEvent;

        public Delay(int start, int lastEvent) {
            this.start = start;
            this.lastEvent = lastEvent;
        }

        public Delay(int start) {
            this(start, -1);
        }

        public Delay() {
            this(-1, -1);
        }

        public boolean isRunning() {
            return this.start >= 0;
        }

        public boolean hasEvent() {
            return this.lastEvent >= 0;
        }

        public float getProgress(float current) {
            if (this.start < 0) return 0;
            float duration = current - this.start;
            if (duration <= 0) return 0;
            if (duration >= MAX_DURATION) return 1;
            return duration / MAX_DURATION;
        }

        public float getEventProgress(float current) {
            if (this.lastEvent < 0) return 0;
            float duration = current - this.lastEvent;
            if (duration <= 0) return 0;
            if (duration >= TIME_TO_STOP) return 1;
            return duration / TIME_TO_STOP;
        }

        public void stopEvent() {
            this.lastEvent = -1;
        }

        public void stop() {
            this.start = -1;
            this.lastEvent = -1;
        }

        public void start(int current) {
            this.start = current;
        }

        public Result tick(int current) {
            if (this.start < 0) return Result.NONE;
            if (current < this.start) {
                this.stop();
                return Result.NONE;
            }
            if (current - this.start >= MAX_DURATION) {
                this.stop();
                return Result.REGENERATE;
            }
            if (this.lastEvent > 0 && current - this.lastEvent >= TIME_TO_STOP) {
                this.stop();
                return Result.REGENERATE;
            }
            if (this.lastEvent < 0) {
                float progress = this.getProgress(current);
                float probability = EVENT_CHANCE * progress;
                if (Math.random() < probability) {
                    this.lastEvent = current;
                    return Result.EVENT;
                }
            }
            return Result.NONE;
        }

        public enum Result {
            REGENERATE,
            EVENT,
            NONE
        }
    }
}