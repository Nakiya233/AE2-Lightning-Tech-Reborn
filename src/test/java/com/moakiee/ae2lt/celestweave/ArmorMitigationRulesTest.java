package com.moakiee.ae2lt.celestweave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

final class ArmorMitigationRulesTest {

    @Test
    void environmentalDamageWinsWhenDamageAlsoBypassesArmor() {
        assertEquals(
                ArmorMitigationRules.DamageClass.ENVIRONMENT,
                ArmorMitigationRules.classify(true, true));
    }

    @Test
    void matrixShieldCancelsEnvironmentalDamage() {
        assertEquals(
                0.0F,
                ArmorMitigationRules.apply(
                        "matrix_shield",
                        ArmorMitigationRules.DamageClass.ENVIRONMENT,
                        8.0F));
    }

    @Test
    void hardDamageRemainsHalfEffectiveAgainstMatrixShield() {
        assertEquals(
                4.0F,
                ArmorMitigationRules.apply(
                        "matrix_shield",
                        ArmorMitigationRules.DamageClass.HARD,
                        8.0F));
    }

    @Test
    void phaseShieldCancelsEveryDamageClass() {
        for (var damageClass : ArmorMitigationRules.DamageClass.values()) {
            assertEquals(0.0F, ArmorMitigationRules.apply("phase_shield", damageClass, 8.0F));
            assertEquals(0.0F, ArmorMitigationRules.apply("phase_shield", damageClass, 1024.0F));
            assertEquals(1.0F, ArmorMitigationRules.apply("phase_shield", damageClass, 1025.0F));
            assertEquals(1024.0F, ArmorMitigationRules.apply("phase_shield", damageClass, 2048.0F));
        }
    }

    @Test
    void phaseStillBillsItsAbsorptionWhenAnExtremeFloatCannotRepresentTheSubtraction() {
        assertEquals(1024.0F, ArmorMitigationRules.preventedDamage(
                "phase_shield", ArmorMitigationRules.DamageClass.ORDINARY, Float.MAX_VALUE));
        assertEquals(Float.MAX_VALUE, ArmorMitigationRules.apply(
                "phase_shield", ArmorMitigationRules.DamageClass.ORDINARY, Float.MAX_VALUE));
        assertFalse(ArmorMitigationRules.extinguishesFire("phase_shield"));
    }

    @Test
    void overloadProtectionHasNoDamageLimit() {
        for (var damageClass : ArmorMitigationRules.DamageClass.values()) {
            assertEquals(0.0F, ArmorMitigationRules.apply("overload_protection", damageClass, Float.MAX_VALUE));
        }
    }

    @Test
    void multidimensionalProtectionCancelsEveryDamageClass() {
        for (var damageClass : ArmorMitigationRules.DamageClass.values()) {
            assertEquals(0.0F, ArmorMitigationRules.apply(
                    "multidimensional_protection",
                    damageClass,
                    Float.MAX_VALUE));
        }
    }
}
