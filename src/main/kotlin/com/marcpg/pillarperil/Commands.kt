package com.marcpg.pillarperil

import com.marcpg.libpg.config.ConfigValueType
import com.marcpg.libpg.config.PaperConfigProvider
import com.marcpg.libpg.data.time.Time
import com.marcpg.libpg.lang.string
import com.marcpg.libpg.sarge.*
import com.marcpg.libpg.util.*
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.util.GameManager
import com.marcpg.pillarperil.game.mode.OriginalGame
import com.marcpg.pillarperil.game.util.GameStarter
import com.marcpg.pillarperil.game.util.QueueManager
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.PPArgumentTypes
import com.marcpg.pillarperil.util.RANDOM_ARENA
import com.marcpg.pillarperil.util.QueueMethod
import com.marcpg.pillarperil.util.trackToFastStats
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player

@Suppress("UnstableApiUsage")
object Commands {
    private val TIME_SUGGESTIONS = listOf("30s", "1min", "2min", "5min", "10min", "30min")

    private fun arenaNames() = listOf(RANDOM_ARENA) + Configuration.arenas.keys.sorted()

    /**
     * Starts a game.
     *
     * Every argument is optional: without them the game uses the default mode, a random arena
     * and everyone online.
     */
    private fun startGame(
        context: CommandContext<CommandSourceStack>,
        modeName: String? = null,
        arenaName: String? = null,
        timeLimit: Time? = null,
    ): Component? {
        val source = context.exec()

        val companion = if (modeName == null) OriginalGame else Registry.modes[modeName]
            ?: return source.locale().component("games.start.invalid_mode", color = NamedTextColor.RED)

        val configured = Configuration.arenas
        if (configured.isEmpty())
            return source.locale().component("games.start.no_arenas", color = NamedTextColor.RED)

        val arena = when (arenaName) {
            null, RANDOM_ARENA -> configured.values.random()
            else -> configured[arenaName]
                ?: return source.locale().component("games.start.unknown_arena", arenaName, color = NamedTextColor.RED)
        }

        val players = runCatching { context.arg<PlayerSelectorArgumentResolver>("players").resolve(context.source) }
            .getOrElse { Bukkit.getOnlinePlayers().toList() }

        if (players.isEmpty())
            return source.locale().component("games.start.no_players", color = NamedTextColor.RED)

        if (players.any { GameManager.player(it, false) != null })
            return source.locale().component("games.start.player_in_game", color = NamedTextColor.RED)

        val id = Game.generateId()

        // Copying a world takes a moment, so the outcome is reported from the callback instead of returned.
        GameStarter.start(id, companion, players, arena, timeLimit) { result ->
            result.onSuccess {
                source.sendMessage(source.locale().component("games.start.success", id, arena.name, color = NamedTextColor.GREEN))
            }.onFailure {
                PillarPeril.LOG.error("Could not start game in arena '${arena.name}'", it)
                it.trackToFastStats()
                source.sendMessage(source.locale().component("games.start.internal_error", color = NamedTextColor.RED))
            }
        }
        return null
    }

    val game = command<CommandSourceStack>("pillar-peril") {
        subcommand("start") {
            require("pillarperil.start")

            // Everything is optional, each level adding one more thing to pin down.
            action { context -> startGame(context) }

            argument("mode", PPArgumentTypes.valuedWithTooltipFromLocale { locale -> Registry.modes.entries.associate { it.key to it.value.gameInfo.description(locale) } }.paper()) {
                action { context -> startGame(context, context.arg("mode")) }

                argument("arena", ExtendedArgumentTypes.valued { arenaNames() }.paper()) {
                    action { context -> startGame(context, context.arg("mode"), context.arg("arena")) }

                    argument("players", ArgumentTypes.players()) {
                        action { context -> startGame(context, context.arg("mode"), context.arg("arena")) }

                        argument("time-limit", StringArgumentType.word()) {
                            suggest(TIME_SUGGESTIONS)
                            action { context ->
                                val raw = context.arg<String>("time-limit")
                                val timeLimit = Time.parse(raw)
                                    ?: return@action context.exec().locale().component("games.time.invalid", raw, color = NamedTextColor.RED)

                                return@action startGame(context, context.arg("mode"), context.arg("arena"), timeLimit)
                            }
                        }
                    }
                }
            }
        }
        subcommand("arenas") {
            require("pillarperil.start")
            action { context ->
                val source = context.exec()
                val arenas = Configuration.arenas

                if (arenas.isEmpty())
                    return@action source.locale().component("games.start.no_arenas", color = NamedTextColor.RED)

                source.sendMessage(component("Configured arenas:", NamedTextColor.GRAY))
                for (arena in arenas.values.sortedBy { it.name }) {
                    val loaded = arena.center() != null
                    source.sendMessage(component("| ", NamedTextColor.DARK_GRAY)
                        .append(component(arena.name, if (loaded) NamedTextColor.GREEN else NamedTextColor.RED))
                        .append(component(" - ${arena.worldName} @ ${arena.cord.x}/${arena.cord.z}", NamedTextColor.GRAY)))

                    // Only shown when set, as most arenas simply take their world as it is.
                    arena.time?.let { source.sendMessage(component("|   time: $it", NamedTextColor.DARK_GRAY)) }
                    if (arena.gameRules.isNotEmpty())
                        source.sendMessage(component("|   game rules: " + arena.gameRules.entries.joinToString { "${it.key}=${it.value}" }, NamedTextColor.DARK_GRAY))
                }
                return@action null
            }
        }
        subcommand("stop") {
            require("pillarperil.stop")

            // Naming the game is only needed while more than one is running.
            action { context ->
                val source = context.exec()
                val running = GameManager.games.values.toList()

                val game = running.singleOrNull()
                    ?: return@action if (running.isEmpty())
                        source.locale().component("games.stop.no_games", color = NamedTextColor.YELLOW)
                    else
                        source.locale().component("games.stop.ambiguous", running.size.toString(), color = NamedTextColor.RED)

                game.end(Game.EndingCause.FORCE)
                return@action source.locale().component("games.stop.success", game.id, color = NamedTextColor.YELLOW)
            }

            argument("game", ExtendedArgumentTypes.valued { GameManager.games.keys }.paper()) {
                action { context ->
                    val source = context.exec()

                    val game = GameManager[context.arg<String>("game")]
                        ?: return@action source.locale().component("games.wrong_id", color = NamedTextColor.RED)

                    game.end(Game.EndingCause.FORCE)
                    return@action source.locale().component("games.stop.success", game.id, color = NamedTextColor.YELLOW)
                }
            }
        }
        subcommand("time") {
            require("pillarperil.time")
            argument("game", ExtendedArgumentTypes.valued { GameManager.games.keys }.paper()) {
                fun timeOperation(name: String, operation: Game.(Time) -> Unit) {
                    subcommand(name) {
                        argument("time", StringArgumentType.word()) {
                            suggest(TIME_SUGGESTIONS)
                            action { context ->
                                val source = context.exec()

                                val game = GameManager[context.arg<String>("game")]
                                    ?: return@action source.locale().component("games.wrong_id", color = NamedTextColor.RED)

                                val raw = context.arg<String>("time")
                                val time = Time.parse(raw)
                                    ?: return@action source.locale().component("games.time.invalid", raw, color = NamedTextColor.RED)

                                game.operation(time)
                                return@action source.locale().component("games.time.success", game.id, game.timeLeft.preciselyFormatted, color = NamedTextColor.GREEN)
                            }
                        }
                    }
                }
                timeOperation("add") { addTime(it) }
                timeOperation("remove") { removeTime(it) }
                timeOperation("set") { setTime(it) }
            }
        }
        subcommand("list") {
            require("pillarperil.list")
            action { context ->
                val source = context.exec()
                if (GameManager.games.isEmpty())
                    return@action component("There are no games running.", NamedTextColor.YELLOW)

                source.sendMessage(component("Running games:"))
                for (game in GameManager.games.values) {
                    val accentColor = game.info.accentColor()
                    source.sendMessage(component("==== ", NamedTextColor.DARK_GRAY).append(component(game.id, accentColor)).append(component(" ====", NamedTextColor.DARK_GRAY)))
                    source.sendMessage(component("> Players: ", NamedTextColor.GRAY).append(component("${game.players.size}/${game.initialPlayers.size}", accentColor)))
                    val disconnected = game.players.filter { it.disconnected }
                    if (disconnected.isNotEmpty())
                        source.sendMessage(component("> Disconnected: ", NamedTextColor.GRAY).append(component(disconnected.joinToString { it.name() }, accentColor)))
                    source.sendMessage(component("> Item Countdown: ", NamedTextColor.GRAY).append(component(game.itemCountdown.toString(), accentColor)))
                    source.sendMessage(component("> Time Left: ", NamedTextColor.GRAY).append(component(game.timeLeft.preciselyFormatted, accentColor)))
                    if (!game.started)
                        source.sendMessage(component("> Starting In: ", NamedTextColor.GRAY).append(component(game.startCountdown.preciselyFormatted, accentColor)))
                    source.sendMessage(component("> Mode: ", NamedTextColor.GRAY).append(component(game.info.namespace, accentColor)))
                    source.sendMessage(component("> Center Location: ", NamedTextColor.GRAY).append(component(game.center.toString(), accentColor)))
                }
                return@action null
            }
            subcommand("raw") {
                action {
                    if (GameManager.games.isEmpty())
                        return@action component("empty")
                    return@action component(GameManager.games.keys.joinToString(";"))
                }
            }
        }
        subcommand("info") {
            require("pillarperil.info")
            argument("game", ExtendedArgumentTypes.valued { GameManager.games.keys }.paper()) {
                action { context ->
                    val source = context.exec()

                    val game = GameManager[context.arg<String>("game")]
                        ?: return@action source.locale().component("games.wrong_id", color = NamedTextColor.RED)

                    return@action miniMessage("""
<dark_gray>=== <yellow>${game.id} <dark_gray> ===
<dark_gray>Center: <yellow>${game.center}
<dark_gray>Time Left: <yellow>${game.timeLeft.preciselyFormatted}${if (game.started) "" else "\n<dark_gray>Starting In: <yellow>${game.startCountdown.preciselyFormatted}"}
<dark_gray>Item Countdown: <yellow>${game.itemCountdown.preciselyFormatted}
<dark_gray>========================
<dark_gray>Mode Name: <yellow>${game.info.name(source.locale())}
<dark_gray>Mode Color: <${game.info.accentColor().asHexString()}>${game.info.accentColor().asHexString()}
<dark_gray>Mode Generator: <yellow>${game.info.vertGen()}
<dark_gray>Mode Item Countdown: <yellow>${game.info.itemCountdown()}
<dark_gray>========================
<dark_gray>Players:
${game.initialPlayers.joinToString { "<dark_gray>| <${if (it !in game.players) "red" else if (it.disconnected) "yellow" else "green"}>${it.name()}" }}
<dark_gray>========================
                    """.trimIndent())
                }
            }
        }
    }

    val queue = command<CommandSourceStack>("queue") {
        require { Configuration.queueEnabled }
        subcommand("join") {
            require { Configuration.queueMethod == QueueMethod.COMMAND && it.executor is Player }
            playerAction { _, player ->
                if (player in QueueManager.queue)
                    return@playerAction player.locale().component("queue.join.already", color = NamedTextColor.YELLOW)

                QueueManager.add(player)
                return@playerAction player.locale().component("queue.join.success", color = NamedTextColor.GREEN)
            }
        }
        subcommand("leave") {
            require { Configuration.queueMethod == QueueMethod.COMMAND && it.executor is Player }
            playerAction { _, player ->
                if (player !in QueueManager.queue)
                    return@playerAction player.locale().component("queue.leave.not_queued", color = NamedTextColor.RED)

                QueueManager.remove(player)
                return@playerAction player.locale().component("queue.leave.success", color = NamedTextColor.YELLOW)
            }
        }
        subcommand("admin") {
            require { it.sender.isOp }
            subcommand("list") {
                action { context ->
                    if (QueueManager.queue.isEmpty())
                        return@action context.exec().locale().component("queue.list.empty", color = NamedTextColor.GREEN)

                    context.feedback(context.exec().locale().component("queue.list.list", QueueManager.queue.size.toString(), color = NamedTextColor.GREEN))
                    for (player in QueueManager.queue) {
                        context.feedback(component("| - ", NamedTextColor.GRAY).append(player.displayName().color(NamedTextColor.WHITE)))
                    }

                    return@action null
                }
            }
            subcommand("add") {
                argument("players", ArgumentTypes.players()) {
                    action { context ->
                        val players = context.arg<PlayerSelectorArgumentResolver>("players").resolve(context.source)

                        if (QueueManager.queue.containsAll(players))
                            return@action context.exec().locale().component("queue.add.already", color = NamedTextColor.YELLOW)

                        players.forEach { QueueManager.add(it) }
                        return@action context.exec().locale().component("queue.add.success", color = NamedTextColor.GREEN)
                    }
                }
            }
            subcommand("remove") {
                argument("players", ArgumentTypes.players()) {
                    action { context ->
                        val players = context.arg<PlayerSelectorArgumentResolver>("players").resolve(context.source)

                        if (players.all { it !in QueueManager.queue })
                            return@action context.exec().locale().component("queue.remove.not_queued", color = NamedTextColor.RED)

                        players.forEach { QueueManager.remove(it) }
                        return@action context.exec().locale().component("queue.remove.success", color = NamedTextColor.YELLOW)
                    }
                }
            }
            subcommand("clear") {
                action { context ->
                    if (QueueManager.queue.isEmpty())
                        return@action context.exec().locale().component("queue.clear.empty", color = NamedTextColor.YELLOW)

                    QueueManager.queue.clear()
                    return@action context.exec().locale().component("queue.clear.success", color = NamedTextColor.YELLOW)
                }
            }
        }
    }

    val ppConfig = command<CommandSourceStack>("pp-config") {
        require("pillarperil.config")
        subcommand("reload") {
            action { context ->
                val locale = context.source.sender.locale()
                val result = Configuration.loadChecking()

                result.second.forEach { context.source.sender.sendMessage(component(it)) }
                return@action locale.component("config.reload", locale.string("config.reload.result.${result.first.name.lowercase()}"), color = NamedTextColor.YELLOW)
            }
        }
        subcommand("modify") {
            argument("path", StringArgumentType.word()) {
                suggest {
                    Configuration.getEntries().forEach { entry ->
                        suggest(entry.key, LiteralMessage("Current: " + entry.value.getBaseValue().toString()))
                    }
                }
                subcommand("get") {
                    action { context ->
                        val locale = context.source.sender.locale()
                        val path = context.arg<String>("path")

                        val obj = (Configuration.provider as PaperConfigProvider).configuration.get(path)
                        return@action when {
                            obj is List<*> -> locale.component("config.get.list", path, color = NamedTextColor.YELLOW)
                                .append(component(obj.joinToString("\n- ", "\n- ") { it.toString() }))

                            obj != null -> locale.component("config.get.object", path, obj.toString(), color = NamedTextColor.YELLOW)

                            else -> locale.component("config.key_not_existing", path, color = NamedTextColor.RED)
                        }
                    }
                }
                subcommand("set") {
                    argument("value", StringArgumentType.greedyString()) {
                        action { context ->
                            val locale = context.source.sender.locale()
                            val path = context.arg<String>("path")
                            val value = context.arg<String>("value")

                            return@action runCatching {
                                when (Configuration.provider.approximatePathType(path)) {
                                    ConfigValueType.STRING -> Configuration.provider.setString(path, value)
                                    ConfigValueType.INT -> Configuration.provider.setInt(path, value.toInt())
                                    ConfigValueType.LONG -> Configuration.provider.setLong(path, value.toLong())
                                    ConfigValueType.DOUBLE -> Configuration.provider.setDouble(path, value.toDouble())
                                    ConfigValueType.BOOLEAN -> Configuration.provider.setBoolean(path, value.toBoolean())
                                    ConfigValueType.LIST, ConfigValueType.MAP -> error("list/map")
                                    else -> error("unknown")
                                }

                                runCatching {
                                    Configuration.save()
                                }.onFailure {
                                    it.trackToFastStats()
                                    error("save")
                                }

                                if (path == "queue.enabled" && value == "true")
                                    context.feedback(locale.component("config.set.note.queue", color = NamedTextColor.RED))

                                locale.component("config.set.confirm", path, value, color = NamedTextColor.YELLOW)
                            }.getOrElse {
                                when (it.message) {
                                    "list/map" -> locale.component("config.set.section_list", color = NamedTextColor.RED)
                                    "save" -> locale.component("config.error", color = NamedTextColor.RED)
                                    else -> locale.component("config.set.invalid", path, value, color = NamedTextColor.RED)
                                }
                            }
                        }
                    }
                }
                fun listOperation(name: String, action: MutableList<Any?>.(String) -> Unit) {
                    subcommand(name) {
                        argument("value", StringArgumentType.greedyString()) {
                            action { context ->
                                val locale = context.source.sender.locale()
                                val path = context.arg<String>("path")
                                val value = context.arg<String>("value")

                                if (Configuration.provider.approximatePathType(path) != ConfigValueType.LIST)
                                    return@action locale.component("config.not_list", path, color = NamedTextColor.RED)

                                val list = Configuration.provider.getList(path)!!.toMutableList()
                                if (name == "remove" && value !in list)
                                    return@action locale.component("config.remove.not_containing", value, path, color = NamedTextColor.RED)

                                list.action(value)
                                Configuration.provider.setList(path, list)

                                runCatching {
                                    Configuration.save()
                                }.onFailure {
                                    it.trackToFastStats()
                                    return@action locale.component("config.error", color = NamedTextColor.RED)
                                }

                                return@action locale.component("config.$name.confirm", value, path, color = NamedTextColor.YELLOW)
                            }
                        }
                    }
                }
                listOperation("add") { add(it) }
                listOperation("remove") { remove(it) }
            }
        }
    }
}
