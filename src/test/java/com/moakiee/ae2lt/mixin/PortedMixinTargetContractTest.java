package com.moakiee.ae2lt.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Read binary shapes without loading client classes on a dedicated-server test JVM. */
class PortedMixinTargetContractTest {
    @Test
    void terminalSlotOverrideHasForgeReleaseMapping() throws Exception {
        // The ordinary target tests read the development AE2 binary. Its vanilla
        // overrides retain Mojmap names, unlike the actual Forge release JAR.
        try (var in = getClass().getClassLoader().getResourceAsStream("ae2lt.refmap.json")) {
            assertNotNull(in, "Generated release refmap must be present");
            var mappings = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("mappings");
            var terminal = mappings.getAsJsonObject("com/moakiee/ae2lt/mixin/client/BigTerminalAmountMixin");
            assertNotNull(terminal, "Terminal mixin must map its inherited vanilla override");
            String descriptor = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;)V";
            assertEquals("Lappeng/client/gui/me/common/MEStorageScreen;m_280092_" + descriptor,
                    terminal.get("renderSlot" + descriptor).getAsString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "big.BigCraftAmountMenuMixin", "big.BigCraftConfirmMenuMixin",
        "big.BigCraftingPlanSummaryMixin", "big.BigCraftingStatusEntryMixin", "big.BigCpuListEntryMixin",
        "client.BigNumberEntryWidgetMixin", "client.BigCraftAmountScreenMixin",
        "client.BigCraftingStatusTableMixin", "client.BigTerminalAmountMixin", "client.BigCpuListMixin",
        "CraftingCPURecordAccessor", "TimeWheelCraftingCPUCyclerMixin"
    })
    void selectorsMatchForgeAe2Binary(String name) throws Exception {
        var mixin = read("com/moakiee/ae2lt/mixin/" + name.replace('.', '/'));
        var declaration = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
        Object value = value(declaration, "value");
        String targetName = value != null ? ((Type) ((List<?>) value).get(0)).getInternalName()
                : ((String) ((List<?>) value(declaration, "targets")).get(0)).replace('.', '/');
        var target = read(targetName);
        for (var handler : mixin.methods) {
            for (var annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                Object selectors = value(annotation, "method");
                if (!(selectors instanceof List<?> methods)) continue;
                for (Object selector : methods) {
                    String text = (String) selector;
                    String methodName = text.contains("(") ? text.substring(0, text.indexOf('(')) : text;
                    var matches = target.methods.stream().filter(m -> m.name.equals(methodName)
                            && (!text.contains("(") || (m.name + m.desc).equals(text))).toList();
                    assertFalse(matches.isEmpty(), name + " missing " + text);
                    if (annotation.desc.endsWith("/Inject;")) {
                        for (var method : matches) assertEquals(method.access & Opcodes.ACC_STATIC,
                                handler.access & Opcodes.ACC_STATIC, name + " static mismatch: " + text);
                    }
                    Object atValue = value(annotation, "at");
                    var points = atValue instanceof List<?> list ? list : Collections.singletonList(atValue);
                    for (var point : points) {
                        if (!(point instanceof AnnotationNode at) || !"INVOKE".equals(value(at, "value"))) continue;
                        String invocation = (String) value(at, "target");
                        if (invocation == null) continue;
                        boolean found = matches.stream().anyMatch(m -> {
                            for (var instruction : m.instructions) {
                                if (instruction instanceof MethodInsnNode call
                                        && invocation.equals("L" + call.owner + ";" + call.name + call.desc)) return true;
                            }
                            return false;
                        });
                        assertTrue(found, name + " missing invocation " + invocation);
                    }
                }
            }
        }
    }
    private static Object value(AnnotationNode node, String key) {
        if (node.values != null) for (int i = 0; i < node.values.size(); i += 2)
            if (key.equals(node.values.get(i))) return node.values.get(i + 1);
        return null;
    }
    private static List<AnnotationNode> annotations(List<AnnotationNode> a, List<AnnotationNode> b) {
        var result = new ArrayList<AnnotationNode>();
        if (a != null) result.addAll(a);
        if (b != null) result.addAll(b);
        return result;
    }
    private static ClassNode read(String name) throws Exception {
        try (var in = PortedMixinTargetContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(in, name);
            var result = new ClassNode();
            new ClassReader(in).accept(result, 0);
            return result;
        }
    }
}
