package com.rushberry.dodgesword;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;

public class DodgeSwordMod implements ModInitializer {
    public static final String MOD_ID = "voidela";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // เรียกใช้งานคลาสอื่นๆ ให้ทำงานตอนเริ่มเกม
        ModComponents.initialize();
        ModItems.initialize();
        DodgeSwordEvents.initialize();
        
        LOGGER.info("Dodge Sword is ready to warp!");
    }
}