package dev.amble.timelordregen.animation;

import dev.amble.lib.animation.AnimatedEntity;
import dev.amble.timelordregen.RegenerationMod;
import dev.drtheo.scheduler.api.common.Scheduler;
import dev.drtheo.scheduler.api.common.TaskStage;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;

public class AnimationSet {
    private final AnimationTemplate template;
    private final EnumMap<AnimationTemplate.Stage, List<Consumer<AnimationTemplate.Stage>>> callbacks;
    private final List<Runnable> finishCallbacks;
    private AnimationTemplate.Stage current;
    private boolean cancelled = false;
    @Getter
    @Nullable private AnimatedEntity target;

    public AnimationSet(AnimationTemplate template) {
        this.template = template;
        this.callbacks = new EnumMap<>(AnimationTemplate.Stage.class);
        this.finishCallbacks = new ArrayList<>();
        this.current = AnimationTemplate.Stage.START;
    }

    private AnimationTemplate.ReferenceWrapper get(AnimationTemplate.Stage stage) {
        return this.template.get(stage);
    }

    public void callback(AnimationTemplate.Stage stage, Consumer<AnimationTemplate.Stage> callback) {
        this.callbacks.computeIfAbsent(stage, s -> new ArrayList<>()).add(callback);
    }

    public void finish(Runnable callback) {
        this.finishCallbacks.add(callback);
    }

    private void runCallbacks(AnimationTemplate.Stage stage) {
        List<Consumer<AnimationTemplate.Stage>> cbs = this.callbacks.get(stage);
        if (cbs != null) {
            for (Consumer<AnimationTemplate.Stage> cb : cbs) {
                try {
                    cb.accept(stage);
                } catch (Exception e) {
                    RegenerationMod.LOGGER.warn("Error in AnimationSet callback for stage {}: {}", stage, e.getMessage());
                }
            }
        }
    }

    private void setStage(AnimationTemplate.Stage stage) {
        this.current = stage;
        this.runCallbacks(stage);
    }

    @Nullable
    private AnimationTemplate.Stage nextStage(AnimationTemplate.Stage current) {
        return current.next();
    }

    public void start(AnimatedEntity target) {
        this.target = target;
        this.cancelled = false;
        this.setStage(AnimationTemplate.Stage.START);
        this.runStage(this.current);
    }

    private void runStage(AnimationTemplate.Stage stage) {
        // 如果已取消或目标丢失，直接完成
        if (this.cancelled || this.target == null) {
            this.finishAnimation();
            return;
        }

        // 如果 stage 为 null，表示所有阶段已完成
        if (stage == null) {
            this.finishAnimation();
            return;
        }

        AnimationTemplate.ReferenceWrapper wrapper = this.get(stage);
        if (wrapper == null) {
            // 没有动画引用，跳过此阶段
            RegenerationMod.LOGGER.warn("No animation reference for stage {}, skipping", stage);
            AnimationTemplate.Stage next = this.nextStage(stage);
            if (next != null) {
                this.setStage(next);
                this.runStage(next);
            } else {
                this.finishAnimation();
            }
            return;
        }

        // 检查动画时长是否有效
        long duration = wrapper.duration();
        if (duration <= 0) {
            RegenerationMod.LOGGER.warn("Invalid duration {} for stage {}, skipping", duration, stage);
            AnimationTemplate.Stage next = this.nextStage(stage);
            if (next != null) {
                this.setStage(next);
                this.runStage(next);
            } else {
                this.finishAnimation();
            }
            return;
        }

        // 播放动画
        this.target.playAnimation(wrapper.reference());

        // 调度下一阶段
        Scheduler.get().runTaskLater(() -> {
            // 再次检查状态
            if (this.cancelled || this.target == null) {
                this.finishAnimation();
                return;
            }
            AnimationTemplate.Stage next = this.nextStage(stage);
            if (next != null) {
                this.setStage(next);
                this.runStage(next);
            } else {
                this.finishAnimation();
            }
        }, TaskStage.END_SERVER_TICK, wrapper.unit(), duration);
    }

    /**
     * 执行所有 finish 回调并清理状态
     */
    private void finishAnimation() {
        if (this.cancelled) return; // 防止重复执行
        this.cancelled = true;
        RegenerationMod.LOGGER.info("AnimationSet finishing with {} callbacks", finishCallbacks.size());
        for (Runnable cb : this.finishCallbacks) {
            try {
                cb.run();
            } catch (Exception e) {
                RegenerationMod.LOGGER.error("Error in AnimationSet finish callback", e);
            }
        }
        // 清理回调列表，防止内存泄漏
        this.finishCallbacks.clear();
        this.target = null; // 释放引用
    }

    /**
     * 取消动画（也会触发 finish）
     */
    public void cancel() {
        if (this.cancelled) return;
        this.finishAnimation();
    }
}