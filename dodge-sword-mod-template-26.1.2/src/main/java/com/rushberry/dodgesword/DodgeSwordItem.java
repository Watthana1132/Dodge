package com.rushberry.dodgesword;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.Comparator;

public class DodgeSwordItem extends Item {

    public DodgeSwordItem(Item.Properties properties) {
        super(properties);
    }

    // แก้จาก boolean เป็น void (ตาม 1.21.2+)
    @Override
    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
        super.hurtEnemy(stack, target, attacker);
    }

    // เอา InteractionResultHolder ออก เหลือแค่ InteractionResult
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // ใช้ getter .isClientSide() แทน .isClientSide
        if (!level.isClientSide()) {
            AABB searchBox = player.getBoundingBox().inflate(32.0D);
            List<LivingEntity> glowingTargets = level.getEntitiesOfClass(LivingEntity.class, searchBox,
                    entity -> entity != player && entity.hasEffect(MobEffects.GLOWING));

            if (!glowingTargets.isEmpty()) {
                LivingEntity target = glowingTargets.stream()
                        .min(Comparator.comparingDouble(player::distanceToSqr))
                        .get();

                // ใช้ level.getRandom() แทน level.random
                double angle = level.getRandom().nextDouble() * 2 * Math.PI;
                double distance = level.getRandom().nextDouble() * 7;
                
                double newX = target.getX() + (Math.cos(angle) * distance);
                double newY = target.getY();
                double newZ = target.getZ() + (Math.sin(angle) * distance);

                player.teleportTo(newX, newY, newZ);

                double dX = target.getX() - player.getX();
                double dY = target.getEyeY() - player.getEyeY();
                double dZ = target.getZ() - player.getZ();
                double diffXZ = Math.sqrt(dX * dX + dZ * dZ);
                
                float yaw = (float) (Mth.atan2(dZ, dX) * (180D / Math.PI)) - 90.0F;
                float pitch = (float) -(Mth.atan2(dY, diffXZ) * (180D / Math.PI));
                
                player.setYRot(yaw);
                player.setXRot(pitch);
                player.setYHeadRot(yaw);

                // เปลี่ยน this เป็น stack เพื่อให้ตรงกับคำสั่งใหม่
                player.getCooldowns().addCooldown(stack, 10);

                level.playSound(null, player.getX(), player.getY(), player.getZ(), 
                        SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);

                target.removeEffect(MobEffects.GLOWING);

                // ส่งค่ากลับแบบใหม่
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }
}