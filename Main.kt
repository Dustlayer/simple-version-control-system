package svcs

import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlin.math.log

class NoDirectoryException(override val message: String) : RuntimeException(message) {
    constructor(file: File) : this("Not a directory: ${file.path}")
}

enum class Command(val command: String, val description: String) {
    CONFIG("config", "Get and set a username.") {
        override fun info(vcs: Vcs) {
            val configName = vcs.name
            if (configName == null) {
                println("Please, tell me who you are.")
                return
            }
            println("The username is $configName.")
        }
        override fun run(vcs: Vcs, params: List<String>) {
            vcs.name = params[0]
            info(vcs)
        }
    },
    ADD("add", "Add a file to the index.") {
        override fun info(vcs: Vcs) {
            val trackedFiles = vcs.trackedFiles
            if (trackedFiles.isEmpty()) {
                println(description)
                return
            }
            println("Tracked files:")
            trackedFiles.forEach { println(it) }
        }
        override fun run(vcs: Vcs, params: List<String>) {
            vcs.addTrackedFile(File(params[0]))
        }
    },
    LOG("log", "Show commit logs.") {
        override fun info(vcs: Vcs) {
            val logEntries = vcs.logEntries
            if (logEntries.isEmpty()) {
                println("No commits yet.")
                return
            }
            for (logEntry in logEntries) {
                println(logEntry.toString())
            }
        }
        override fun run(vcs: Vcs, params: List<String>) {
            info(vcs)
        }
    },
    COMMIT("commit", "Save changes.") {
        override fun info(vcs: Vcs) {
            println("Message was not passed.")
        }
        override fun run(vcs: Vcs, params: List<String>) {
            vcs.addCommit(params[0])
        }
    },
    CHECKOUT("checkout", "Restore a file.") {
        override fun info(vcs: Vcs) {
            println("Commit id was not passed.")
        }
        override fun run(vcs: Vcs, params: List<String>) {
            vcs.checkout(params[0])
        }
    };

    fun execute(vcs: Vcs, params: List<String>) {
        if (params.isEmpty()) {
            info(vcs)
            return
        }

        run(vcs, params)
    }

    abstract fun info(vcs: Vcs)
    abstract fun run(vcs: Vcs, params: List<String>)

    companion object {
        fun fromCommand(command: String?): Command? {
            return Command.values().firstOrNull { it.command == command }
        }
    }
}

class LogEntry(val id: String, val author: String, val comment: String) {
    override fun toString(): String {
        return "commit $id\nAuthor: $author\n$comment\n"
    }

    fun toLogLine(): String {
        return "id:$id&&&author:$author&&&comment:$comment"
    }

    companion object {
        fun fromLine(line: String): LogEntry {
            val split = line.split("&&&").map { it.split(':') }
            val id = split.first { it[0] == "id" }[1]
            val author = split.first { it[0] == "author" }[1]
            val comment = split.first { it[0] == "comment" }[1]
            return LogEntry(id, author, comment)
        }
    }
}

class Vcs(val basePath: File) {
    val vcsDir: File
    val configFile: File
    val indexFile: File
    val commitsDir: File
    val logFile: File

    init {
        if (!basePath.isDirectory) throw NoDirectoryException(basePath)
        vcsDir = basePath.resolve("vcs")
        vcsDir.mkdir()
        configFile = vcsDir.resolve("config.txt")
        configFile.createNewFile()
        indexFile = vcsDir.resolve("index.txt")
        indexFile.createNewFile()
        commitsDir = vcsDir.resolve("commits")
        commitsDir.mkdir()
        logFile = vcsDir.resolve("log.txt")
        logFile.createNewFile()
    }

    var name: String?
        get() = configFile.readText().trim().takeIf { it.isNotEmpty() }
        set(value) = configFile.writeText(value ?: "")

    val trackedFiles: List<File>
        get() = indexFile.readLines().filter { it.isNotEmpty() }.map { File(it.trim()) }

    val logEntries: List<LogEntry>
    get() {
        return logFile.readLines().filter { it.isNotEmpty() }.map { LogEntry.fromLine(it) }
    }

    fun addTrackedFile(file: File) {
        if (!file.exists()) throw FileNotFoundException("Can't find '${file.path}'.")
        indexFile.appendText("${file.path}\n")
        println("The file '${file.path}' is tracked.")
    }

    fun addCommit(comment: String) {
        val allFileBytes = trackedFiles.map { it.readBytes() }.flatMap { it.asIterable() }.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(allFileBytes)
        val id = digest.fold("") { str, it -> str + "%02x".format(it) }
        val logEntriesBefore = logEntries

        if (logEntriesBefore.any { it.id == id }) {
            println("Nothing to commit.")
            return
        }

        val newCommitDir = commitsDir.resolve(id)
        for (trackedFile in trackedFiles) {
            trackedFile.copyTo(newCommitDir.resolve(trackedFile.name))
        }
        addLogEntry(LogEntry(id, name ?: "", comment))
        println("Changes are committed.")
    }

    private fun addLogEntry(logEntry: LogEntry) {
        logFile.writeText("${logEntry.toLogLine()}\n${logFile.readText()}")
    }

    fun checkout(id: String) {
        if (!logEntries.any { it.id == id }) {
            println("Commit does not exist.")
            return
        }
        val commitDir = commitsDir.resolve(id)
        for (f in commitDir.listFiles()!!) {
            f.copyTo(basePath.resolve(f.name), overwrite = true)
        }
        println("Switched to commit $id.")
    }
}

fun printHelp() {
    println("These are SVCS commands:")
    val longest = Command.values().maxOf { it.command.length }
    for (command in Command.values()) {
        println("${command.command.padEnd(longest + 3, ' ')}${command.description}")
    }
}

fun main(args: Array<String>) {
    val commandInput = args.firstOrNull { !it.startsWith("-") }
    val params = args.filter { it != commandInput }

    val command = Command.fromCommand(commandInput)

    if (command == null || params.contains("--help")) {
        if (commandInput?.isNotEmpty() == true) {
            println("'$commandInput' is not a SVCS command.")
            return
        }
        printHelp()
        return
    }

    val vcs = Vcs(File(System.getProperty("user.dir")))

    try {
        command!!.execute(vcs, params)
    } catch (e: Exception) {
        println(e.message)
    }

}

