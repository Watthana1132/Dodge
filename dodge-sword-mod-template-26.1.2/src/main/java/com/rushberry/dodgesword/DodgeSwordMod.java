package com.rushberry.dodgesword;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DodgeSwordMod implements ModInitializer {
    public static final String MOD_ID = "dodgesword";
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