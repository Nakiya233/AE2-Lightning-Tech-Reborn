package com.moakiee.ae2lt.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class OverloadProcessingCompatibilityRecipeContractTest {
    private static final Path RECIPE_ROOT = Path.of(
            "src/main/resources/data/ae2lt/recipes/overload_processing");

    @Test
    void advancedAeRecipesKeepUpstreamConventionTags() throws Exception {
        assertTag("aae_quantum_processor.json", 1, "forge:storage_blocks/redstone");
    }

    @Test
    void unsupportedExtendedAeProcessorIsNotShippedOnForge() {
        // The Forge ExtendedAE version has no concurrent processor item.
        assertFalse(Files.exists(RECIPE_ROOT.resolve("eae_concurrent_processor.json")));
    }

    @Test
    void appliedFluxRecipesKeepUpstreamConventionTags() throws Exception {
        assertTag("appflux_harden_insulating_resin.json", 2, "forge:silicon");
        assertTag("appflux_harden_insulating_resin.json", 4, "forge:dusts/glowstone");
    }

    @Test
    void ae2FluixPearlKeepsUpstreamConventionTags() throws Exception {
        assertTag("ae2_fluix_pearl.json", 0, "forge:ender_pearls");
        assertTag("ae2_fluix_pearl.json", 1, "forge:dusts/fluix");
    }

    private static void assertTag(String filename, int inputIndex, String expectedTag) throws Exception {
        JsonObject input = recipe(filename).getAsJsonArray("inputs")
                .get(inputIndex)
                .getAsJsonObject();
        JsonObject ingredient = input.getAsJsonObject("ingredient");

        assertEquals(expectedTag, ingredient.get("tag").getAsString(), filename);
        assertFalse(ingredient.has("item"), filename);
    }

    private static JsonObject recipe(String filename) throws Exception {
        return JsonParser.parseString(Files.readString(RECIPE_ROOT.resolve(filename))).getAsJsonObject();
    }
}
