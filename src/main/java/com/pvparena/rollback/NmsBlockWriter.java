package com.pvparena.rollback;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class NmsBlockWriter {
    private Method craftWorldGetHandle;
    private Method craftBlockDataGetState;
    private Method craftBlockDataFromData;
    private Constructor<?> blockPosCtor;
    private Method serverLevelSetBlock;
    private Method serverLevelGetBlockState;
    private int updateFlags = 3;

    public boolean setBlock(World world, int x, int y, int z, String blockDataString) {
        if (world == null || blockDataString == null || blockDataString.isBlank()) {
            return false;
        }
        try {
            ensureInit();
            Object serverLevel = craftWorldGetHandle.invoke(world);
            BlockData blockData = Bukkit.createBlockData(blockDataString);
            Object nmsState = craftBlockDataGetState.invoke(blockData);
            Object blockPos = blockPosCtor.newInstance(x, y, z);
            int paramCount = serverLevelSetBlock.getParameterCount();
            if (paramCount >= 4) {
                serverLevelSetBlock.invoke(serverLevel, blockPos, nmsState, updateFlags, 0);
            } else {
                serverLevelSetBlock.invoke(serverLevel, blockPos, nmsState, updateFlags);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String getBlockDataString(World world, int x, int y, int z) {
        if (world == null) {
            return null;
        }
        try {
            ensureInit();
            Object serverLevel = craftWorldGetHandle.invoke(world);
            Object blockPos = blockPosCtor.newInstance(x, y, z);
            Object nmsState = serverLevelGetBlockState.invoke(serverLevel, blockPos);
            if (nmsState == null) {
                return null;
            }
            Object blockData = craftBlockDataFromData.invoke(null, nmsState);
            if (!(blockData instanceof BlockData data)) {
                return null;
            }
            return data.getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void ensureInit() throws Exception {
        if (craftWorldGetHandle != null) {
            return;
        }
        Class<?> craftWorldClass = Class.forName("org.bukkit.craftbukkit.CraftWorld");
        craftWorldGetHandle = craftWorldClass.getMethod("getHandle");

        Class<?> craftBlockDataClass = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData");
        craftBlockDataGetState = craftBlockDataClass.getMethod("getState");
        Class<?> blockStateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
        craftBlockDataFromData = craftBlockDataClass.getMethod("fromData", blockStateClass);

        Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
        blockPosCtor = blockPosClass.getConstructor(int.class, int.class, int.class);

        Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
        serverLevelGetBlockState = serverLevelClass.getMethod("getBlockState", blockPosClass);
        Method preferred = null;
        for (Method method : serverLevelClass.getMethods()) {
            if (!method.getName().equals("setBlock")) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length >= 3 && params[0] == blockPosClass && params[1] == blockStateClass && params[2] == int.class) {
                preferred = method;
                break;
            }
        }
        if (preferred == null) {
            throw new NoSuchMethodException("ServerLevel#setBlock not found");
        }
        serverLevelSetBlock = preferred;

        try {
            Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block");
            try {
                Field updateAllField = blockClass.getField("UPDATE_ALL");
                updateFlags = updateAllField.getInt(null);
            } catch (Throwable ignored) {
                Field updateNeighborsField = blockClass.getField("UPDATE_NEIGHBORS");
                Field updateClientsField = blockClass.getField("UPDATE_CLIENTS");
                updateFlags = updateNeighborsField.getInt(null) | updateClientsField.getInt(null);
            }
        } catch (Throwable ignored) {
            updateFlags = 3;
        }
    }
}
