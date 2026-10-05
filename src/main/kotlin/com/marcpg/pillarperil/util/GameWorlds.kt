package com.marcpg.pillarperil.util

import com.marcpg.libpg.util.bukkitRun
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.pillarperil.PillarPeril
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.generator.ChunkGenerator
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.walk

enum class DisposableWorldMode {
    /** A brand-new, completely empty world. Instant and takes up practically no space. */
    VOID,

    /** A full copy of the world the game was requested in. Only sane for small, purpose-built worlds. */
    COPY,
}

/**
 * Creates and deletes the temporary worlds that games can run in.
 *
 * Running a game in a world of its own makes rolling the arena back unnecessary: the whole world is
 * thrown away afterwards, so there is no way for a block or entity change to be missed, no matter what caused it.
 */
object GameWorlds {
    // A player who died in the final tick is back on their feet well within this window.
    private const val DISPOSE_ATTEMPTS = 5
    private const val DISPOSE_RETRY_TICKS = 20L

    // Never copied along, as they either identify the original world or belong to its players.
    // `metadata.dat` holds Paper's world identity: keeping it makes the copy get rejected as a duplicate world.
    private val excludedFromCopy = setOf("session.lock", "uid.dat", "metadata.dat", "playerdata", "stats", "advancements")

    /**
     * Provides the location a game should actually run at.
     *
     * If disposable worlds are disabled, that is simply [requested]. Otherwise a temporary world is
     * created and the same coordinates within that world are returned instead.
     *
     * The [callback] runs on the main thread, immediately for [DisposableWorldMode.VOID] and once the
     * copy has finished for [DisposableWorldMode.COPY].
     */
    fun prepare(id: String, requested: Location, callback: (Result<Location>) -> Unit) {
        if (!Configuration.disposableWorldsEnabled) {
            callback(Result.success(requested))
            return
        }

        // Lowercased because a world's dimension key is, so its directory would not match otherwise.
        val name = Configuration.disposableWorldName("id" to id).lowercase()
        if (Bukkit.getWorld(name) != null) {
            callback(Result.failure(IllegalStateException("A world called '$name' is already loaded.")))
            return
        }

        // Worlds do not always live directly in the world container: since 1.21.9 every world but the
        // primary one sits in `<level>/dimensions/<namespace>/`. Placing the new world next to the one
        // it is based on keeps this working on either layout.
        val parent = requested.world.worldFolder.toPath().parent ?: Bukkit.getWorldContainer().toPath()

        when (Configuration.disposableWorldMode) {
            DisposableWorldMode.VOID -> callback(runCatching { createVoid(name, parent, requested) })
            DisposableWorldMode.COPY -> createCopy(name, parent, requested, callback)
        }
    }

    /**
     * Unloads a temporary world without saving it and deletes it from disk.
     *
     * Reports the outcome itself, since it is not always immediate: a world holding a player who died
     * in the game's final moments refuses to unload until that player is out of it.
     */
    fun dispose(world: World, attempt: Int = 1) {
        val folder = world.worldFolder.toPath()

        evacuate(world)

        world.isAutoSave = false
        if (Bukkit.unloadWorld(world, false)) {
            PillarPeril.LOG.info("Deleted the temporary world '${world.name}'.")

            Bukkit.getScheduler().runTaskAsynchronously(PillarPeril.PLUGIN, Runnable {
                runCatching { folder.toFile().deleteRecursively() }
                    .onFailure { PillarPeril.LOG.warn("Could not delete the temporary world folder '$folder': ${it.message}") }
            })
            return
        }

        // Somebody is still inside, which in practice means they died as the game ended and are sitting
        // on the death screen - they cannot be teleported out until they are back alive, which happens
        // on its own shortly. Giving up here used to leave the world loaded and on disk until a restart.
        if (attempt < DISPOSE_ATTEMPTS) {
            bukkitRunLater(DISPOSE_RETRY_TICKS) { dispose(world, attempt + 1) }
            return
        }

        PillarPeril.LOG.warn(
            "Could not unload the temporary world '${world.name}' after $attempt attempts " +
                "(${world.players.size} player(s) still inside), leaving it for the next startup."
        )
    }

    /** Gets everyone out of a world, so that it can be unloaded. */
    private fun evacuate(world: World) {
        if (world.players.isEmpty()) return

        val fallback = Configuration.getSpawnLocation(Bukkit.getWorlds().first())
        world.players.toList().forEach {
            // A teleport is silently dropped for a player who is still on the death screen.
            it.reviveIfDead()
            it.teleport(fallback)
        }
    }

    /**
     * Deletes temporary worlds left over from a previous run.
     *
     * Unloading and deleting happens when a game ends, which never runs if the server crashed or was
     * killed mid-game. Sweeping them at startup keeps those from piling up forever.
     */
    fun cleanupLeftovers() {
        val pattern = Configuration.disposableWorldName.base
        val prefix = pattern.substringBefore("{id}")
        val suffix = pattern.substringAfter("{id}", "")

        // Without a placeholder there is nothing distinguishing a temporary world from a real one.
        if (!pattern.contains("{id}") || (prefix.isEmpty() && suffix.isEmpty())) {
            PillarPeril.LOG.warn("`disposable-worlds.name` needs a '{id}' placeholder, skipping the leftover cleanup.")
            return
        }

        val loaded = Bukkit.getWorlds().map { it.name.lowercase() }.toSet()

        // Both the world container and, since 1.21.9, every loaded world's own directory can hold these.
        val searched = (Bukkit.getWorlds().mapNotNull { it.worldFolder.parentFile } + Bukkit.getWorldContainer())
            .distinctBy { it.absolutePath }

        val leftovers = searched.flatMap { it.listFiles().orEmpty().asIterable() }.filter {
            it.isDirectory &&
                it.name.length > prefix.length + suffix.length &&
                it.name.startsWith(prefix) && it.name.endsWith(suffix) &&
                it.name.lowercase() !in loaded &&
                // A dimension directory has no level.dat of its own, so its own markers are used instead.
                (File(it, "level.dat").isFile || File(it, "region").isDirectory || File(it, "paper-world.yml").isFile)
        }

        if (leftovers.isEmpty()) return

        leftovers.forEach {
            if (it.deleteRecursively()) {
                PillarPeril.LOG.info("Deleted the leftover temporary world '${it.name}'.")
            } else {
                PillarPeril.LOG.warn("Could not delete the leftover temporary world '${it.name}'.")
            }
        }
    }

    /**
     * Removes a world folder left over under the same name.
     *
     * Only ever reached for a world that is not loaded, as [prepare] refuses those, so this cannot
     * touch a world in use. Without it a leftover folder would silently be loaded as the "new" world.
     */
    private fun clearStaleFolder(parent: Path, name: String) {
        val folder = parent.resolve(name).toFile()
        if (!folder.isDirectory) return

        if (folder.deleteRecursively()) {
            PillarPeril.LOG.warn("Removed a stale '$name' world folder before reusing the name.")
        } else {
            error("A leftover '$name' world folder is in the way and could not be removed.")
        }
    }

    private fun createVoid(name: String, parent: Path, requested: Location): Location {
        clearStaleFolder(parent, name)

        val world = WorldCreator(name)
            .environment(requested.world.environment)
            .generator(VoidChunkGenerator)
            .createWorld() ?: error("Could not create the temporary world '$name'.")

        world.isAutoSave = false
        return Location(world, requested.x, requested.y, requested.z, requested.yaw, requested.pitch)
    }

    private fun createCopy(name: String, parent: Path, requested: Location, callback: (Result<Location>) -> Unit) {
        val source = requested.world
        val from = source.worldFolder.toPath()
        val to = parent.resolve(name)

        // Measuring the folder is disk work, while saving has to happen on the main thread, so this hops
        // between the two: measure, save, copy, load. Measuring first keeps an oversized world from
        // being saved on the main thread just to be refused right afterwards.
        runAsync {
            val checked = runCatching { checkCopyable(source.name, from) }

            bukkitRun {
                checked.onFailure {
                    callback(Result.failure(it))
                    return@bukkitRun
                }

                val prepared = runCatching { clearStaleFolder(parent, name) }
                prepared.onFailure {
                    callback(Result.failure(it))
                    return@bukkitRun
                }

                source.save() // Flush to disk, so the copy is not missing recent changes.

                runAsync {
                    val copied = runCatching { copyWorldFolder(from, to) }

                    bukkitRun {
                        callback(copied.mapCatching {
                            val world = WorldCreator(name)
                                .copy(source)
                                .createWorld() ?: error("Could not load the copied world '$name'.")

                            world.isAutoSave = false
                            Location(world, requested.x, requested.y, requested.z, requested.yaw, requested.pitch)
                        }.onFailure { runCatching { to.toFile().deleteRecursively() } })
                    }
                }
            }
        }
    }

    private fun runAsync(task: () -> Unit) =
        Bukkit.getScheduler().runTaskAsynchronously(PillarPeril.PLUGIN, Runnable(task))

    /** Every path that gets copied along, excluding the world folder itself. */
    @OptIn(ExperimentalPathApi::class)
    private fun copyablePaths(from: Path): List<Path> = from.walk()
        .filter { it != from && it.relativeTo(from).none { part -> part.name in excludedFromCopy } }
        .toList()

    /** Throws if the world is larger than the configured limit, so nobody accidentally clones a survival world. */
    private fun checkCopyable(sourceName: String, from: Path) {
        val limit = Configuration.disposableWorldMaxCopySize.toLong() * 1024L * 1024L
        if (limit <= 0) return

        val size = copyablePaths(from).sumOf { runCatching { it.fileSize() }.getOrDefault(0L) }
        if (size > limit) {
            error(
                "World '$sourceName' is ${size / 1024 / 1024} MiB, which is above the configured " +
                    "`disposable-worlds.max-copy-size` of ${Configuration.disposableWorldMaxCopySize} MiB. " +
                    "Games should be started in a small, purpose-built world when using the 'copy' mode."
            )
        }
    }

    private fun copyWorldFolder(from: Path, to: Path) {
        to.createDirectories()

        for (path in copyablePaths(from)) {
            val target = to.resolve(path.relativeTo(from).toString())

            if (path.isDirectory()) {
                target.createDirectories()
            } else {
                target.parent?.createDirectories()
                path.copyTo(target, overwrite = true)
            }
        }
    }
}

/** Generates nothing at all, leaving a world of pure air for the game to build its pillars in. */
object VoidChunkGenerator : ChunkGenerator() {
    override fun shouldGenerateNoise(): Boolean = false
    override fun shouldGenerateSurface(): Boolean = false
    override fun shouldGenerateCaves(): Boolean = false
    override fun shouldGenerateDecorations(): Boolean = false
    override fun shouldGenerateMobs(): Boolean = false
    override fun shouldGenerateStructures(): Boolean = false

    // Without this, the server searches an entirely empty world for a valid spawn on creation.
    override fun getFixedSpawnLocation(world: World, random: Random): Location =
        Location(world, 0.0, Configuration.platformHeight + 1.0, 0.0)
}
