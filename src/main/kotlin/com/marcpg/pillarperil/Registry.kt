package com.marcpg.pillarperil

import com.marcpg.pillarperil.game.GameModifierCompanion
import com.marcpg.pillarperil.game.mode.ChaosGame
import com.marcpg.pillarperil.game.mode.ItemShuffleGame
import com.marcpg.pillarperil.game.mode.OriginalGame
import com.marcpg.pillarperil.generation.horizontal.CircularHorGen
import com.marcpg.pillarperil.generation.horizontal.RandomHorGen

object Registry {
    val horizontalGenerators = listOf(
        CircularHorGen,
        RandomHorGen,
    ).associateBy { it.namespace }

    val modes = listOf(
        ChaosGame,
        ItemShuffleGame,
        OriginalGame,
    ).associateBy { it.gameInfo.namespace }

    val modifiers = listOf<GameModifierCompanion<*>>(
    ).associateBy { it.modifierInfo.namespace }

    fun load() {
        PillarPeril.LOG.info("[Registry] Loaded ${horizontalGenerators.size} horizontal generators as Map<Name, HorGen>.")

        PillarPeril.LOG.info("[Registry] Loaded ${modes.size} modes as Map<Name, Mode>.")
        PillarPeril.LOG.info("[Registry] Loaded ${modifiers.size} game modifiers as Map<Name, Modifier>.")
    }
}
