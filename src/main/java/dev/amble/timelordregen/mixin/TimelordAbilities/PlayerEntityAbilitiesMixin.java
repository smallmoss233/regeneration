package dev.amble.timelordregen.mixin.TimelordAbilities;

import dev.amble.timelordregen.api.RegenerationCapable;
import dev.amble.timelordregen.api.RegenerationInfo;
import dev.amble.timelordregen.api.TimelordAbilities;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityAbilitiesMixin {

    @Unique
    private int timelord$regenBoostTimer = 0;

    @Unique
    private float timelord$lastSaturation = -1.0f;

    @Unique
    private int timelord$lastFoodLevel = -1;

    // 反射访问 HungerManager.saturationLevel，用于饱食度恢复加成
    @Unique
    private static final java.lang.reflect.Field timelord$SATURATION_FIELD;

    static {
        java.lang.reflect.Field f = null;
        try {
            f = HungerManager.class.getDeclaredField("saturationLevel");
            f.setAccessible(true);
        } catch (Exception ignored) {
            try {
                f = HungerManager.class.getDeclaredField("foodSaturationLevel");
                f.setAccessible(true);
            } catch (Exception ignored2) {}
        }
        timelord$SATURATION_FIELD = f;
    }

    @Unique
    private boolean timelord$isTimelord() {
        PlayerEntity player = (PlayerEntity)(Object) this;
        return player instanceof RegenerationCapable capable && capable.isTimelord();
    }

    // ========== 饥饿消耗 -25% ==========
    @ModifyVariable(method = "addExhaustion", at = @At("HEAD"), argsOnly = true)
    private float timelord$modifyExhaustion(float exhaustion) {
        if (timelord$isTimelord()) {
            return TimelordAbilities.modifyExhaustion(exhaustion);
        }
        return exhaustion;
    }

    // ========== 常驻 10% 抗性 ==========
    @ModifyVariable(method = "damage", at = @At("HEAD"), argsOnly = true)
    private float timelord$applyResistance(float amount, DamageSource source) {
        if (timelord$isTimelord()) {
            RegenerationInfo info = ((RegenerationCapable)(Object) this).getRegenerationInfo();
            if (info != null && !info.isInvulnerable()) {
                return TimelordAbilities.applyResistance(amount);
            }
        }
        return amount;
    }

    // ========== 饥饿伤害免疫 ==========
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void timelord$cancelStarveDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (TimelordAbilities.isImmuneToStarvation((PlayerEntity)(Object) this, source)) {
            cir.setReturnValue(false);
        }
    }

    // ========== 饱食度恢复 +35% + 生命恢复 1.5x + 氧气消耗 75% ==========
    @Inject(method = "tick", at = @At("TAIL"))
    private void timelord$tickAbilities(CallbackInfo ci) {
        if (!timelord$isTimelord()) {
            this.timelord$regenBoostTimer = 0;
            this.timelord$lastSaturation = -1.0f;
            this.timelord$lastFoodLevel = -1;
            return;
        }

        PlayerEntity player = (PlayerEntity)(Object) this;
        HungerManager hungerManager = player.getHungerManager();
        float currentSaturation = hungerManager.getSaturationLevel();
        int currentFoodLevel = hungerManager.getFoodLevel();

        // --- 饱食度恢复 +35%：检测食物消耗 ---
        if (timelord$lastSaturation >= 0 && timelord$lastFoodLevel >= 0) {
            if (currentFoodLevel > timelord$lastFoodLevel) {
                // 计算这一 tick 实际增加的饱和度
                float saturationGained = currentSaturation - timelord$lastSaturation;
                if (saturationGained > 0.001f && timelord$SATURATION_FIELD != null) {
                    float bonusSaturation = saturationGained * 0.35f;
                    // 防止超过饱和度上限（饱和度通常不超过 foodLevel）
                    float maxSaturation = currentFoodLevel;
                    float actualBonus = Math.min(bonusSaturation, maxSaturation - currentSaturation);
                    if (actualBonus > 0) {
                        try {
                            timelord$SATURATION_FIELD.setFloat(hungerManager, currentSaturation + actualBonus);
                            currentSaturation += actualBonus; // 更新本地变量，确保 last 记录正确
                        } catch (IllegalAccessException ignored) {}
                    }
                }
            }
        }

        timelord$lastSaturation = currentSaturation;
        timelord$lastFoodLevel = currentFoodLevel;

        // --- 生命恢复 1.5x ---
        if (player.getHealth() < player.getMaxHealth()) {
            this.timelord$regenBoostTimer++;
            if (this.timelord$regenBoostTimer >= 160) {
                this.timelord$regenBoostTimer = 0;
                if (player.getHungerManager().getFoodLevel() > 0) {
                    player.heal(1.0f);
                    player.getHungerManager().addExhaustion(1.0f);
                }
            }
        } else {
            this.timelord$regenBoostTimer = 0;
        }

        // --- 氧气消耗 75% ---
        if (player.isSubmergedInWater() && TimelordAbilities.shouldCompensateAirLoss(player.age)) {
            int maxAir = player.getMaxAir();
            int currentAir = player.getAir();
            if (currentAir < maxAir) {
                player.setAir(Math.min(maxAir, currentAir + 1));
            }
        }
    }
}