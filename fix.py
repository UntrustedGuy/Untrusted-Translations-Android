with open('app/src/main/java/com/untrustedtranslations/android/ui/TranslationApp.kt', 'r', encoding='utf-8') as f:
    content = f.read()

import re

# Find the exact corrupted block:
#                 },
#             )
#                 // release ?" committing every tick re-rendered the page bitmap per pixel moved.
#                 var sliderPercent by remember(selectedBlock.id, selectedBlock.style.fontSizeSp) {
pattern = r'(Spacer\(Modifier\.width\(8\.dp\)\)\n\s*\}\,\n\s*\))\n\s*// release'
replacement = r'''\1
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(14.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ManipulablePagePreview(
                page = page,
                selectedBlockIndex = vm.selectedBlockIndex,
                onSelectBlock = vm::selectBlock,
                onDeselectAll = vm::onDeselectAll,
                onDeleteBlock = vm::deleteCurrentBlock,
                onDuplicateBlock = vm::duplicateBlock,
                onTransformCommitted = vm::commitPageTransform,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            val selectedBlock = page.blocks.getOrNull(vm.selectedBlockIndex)
            if (selectedBlock?.applied == true) {
                val committedPercent = (selectedBlock.style.fontSizeSp / BASE_TEXT_SIZE_SP * 100)
                    .coerceIn(25f, 400f)
                // release'''

content = re.sub(pattern, replacement, content)

with open('app/src/main/java/com/untrustedtranslations/android/ui/TranslationApp.kt', 'w', encoding='utf-8') as f:
    f.write(content)
