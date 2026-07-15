package dev.amble.timelordregen.mixin.TimelordAbilities;

import dev.amble.timelordregen.api.RegenerationCapable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class LivingEntitySprintMixin {

    @Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
    private void timelord$preventSprintCancel(boolean sprinting, CallbackInfo ci) {
        if (sprinting) return;

        LivingEntity entity = (LivingEntity)(Object) this;
        if (!(entity instanceof PlayerEntity player)) return;
        if (!(player instanceof RegenerationCapable capable && capable.isTimelord())) return;

        if (player.isOnGround()
                && !player.isSneaking()
                && !player.isUsingItem()
                && !player.isTouchingWater()
                && !player.hasStatusEffect(StatusEffects.BLINDNESS)) {
            ci.cancel();
        }
    }
}