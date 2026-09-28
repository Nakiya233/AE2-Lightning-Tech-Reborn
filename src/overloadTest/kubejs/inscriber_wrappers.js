// Isolated regression fixture; copy into the test world's kubejs/server_scripts only.
ServerEvents.recipes(event => {
    let modified = 0;
    let removed = 0;
    event.forEachRecipe({ type: 'ae2lt:overload_processing' }, recipe => {
        const id = String(recipe.getId());
        if (id.startsWith('ae2lt:derived/inscriber/ae2lt_overload/inscriber/processor_fixture/')) {
            const json = recipe.json;
            json.addProperty('totalEnergy', 123456);
            event.remove({ id: id });
            event.custom(json).id(id);
            modified++;
        }
        if (id.startsWith('ae2lt:derived/inscriber/ae2lt_overload/inscriber/removed_processor_fixture/')) {
            event.remove({ id: id });
            removed++;
        }
    });
    if (modified !== 1 || removed !== 1) {
        throw new Error('Inscriber wrappers were not present before KJS: modified=' + modified + ', removed=' + removed);
    }
    event.remove({ id: 'ae2lt:overload_processing/ae2_calculation_processor' });
    event.shapeless('minecraft:cobblestone', ['minecraft:stone']).id('ae2lt_overload:kjs_test_marker');
    console.info('AE2LT inscriber ordering PASS: modified wrapper, removed wrapper, removed manual recipe');
});
