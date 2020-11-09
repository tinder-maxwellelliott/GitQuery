/*
 * © 2019 Match Group, LLC.
 */

package com.tinder.gitquery.core

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.stream.Collectors.toList


/**
 * A utility class that given a yaml file, describing a set of files in a remote git repo, and an
 * intermediate (repo) directory, query, fetch and sync those files into a given output directory.
 *
 * For more details see the README.md file in the root of this project.
 */
object GitQueryCore {
    private var verbose = false

    /**
     * Use the includeGlob and excludeGlob values to initialize or update the config file.
     *
     * @param configFile path to the config file.
     * @param config a yaml file that describe a set of files to fetch/sync from a given repository
     * @param verbose if true, it will print its operations to standard out.
     */
    fun initializeConfig(
        configFile: String,
        config: GitQueryConfig,
        verbose: Boolean = false,
    ) {
        this.verbose = verbose

        val actualRepoDirectory = config.getActualRepoPath()

        prepareRepo(config.remote, config.branch, actualRepoDirectory)

        val actualRepoPath = Paths.get(actualRepoDirectory)
        val files = HashMap<String, Any>()
        val repoSha = repoSha(actualRepoDirectory)

        val excludeMatchers = ArrayList<PathMatcher>(config.excludeGlobs.size)
        for (excludeGlob in config.excludeGlobs) {
            excludeMatchers.add(FileSystems.getDefault().getPathMatcher("glob:${excludeGlob}"))
        }

        for (glob in config.includeGlobs) {
            val matcher = FileSystems.getDefault().getPathMatcher("glob:${glob}")
            Files.walk(actualRepoPath)
                .collect(toList())
                .filter { it: Path? ->
                    it?.let { path ->
                        val matchesInclude = matcher.matches(path)
                        var excluded = false
                        for (excludeMatcher in excludeMatchers) {
                            if (excluded) break
                            excluded = excludeMatcher.matches(path)
                        }
                        matchesInclude && !excluded
                    } ?: false
                }
                .map {
                    if (verbose) {
                        println(it)
                    }
                    val relativePath = it.toString()
                        .substringAfter(actualRepoPath.toString())
                        .substringAfter("/")
                    if (config.flatFiles) {
                        files[relativePath] = repoSha
                    } else {
                        insertNested(files, relativePath, repoSha)
                    }
                }
        }

        config.files = toSortedMap(files)
        config.save(File(configFile))
        println("GitQuery: init complete: $configFile")
    }

    @Suppress("UNCHECKED_CAST")
    private fun toSortedMap(map: java.util.HashMap<String, Any>): Map<String, Any> {
        return map.mapValues {
            if (it.value is HashMap<*, *>) {
                toSortedMap(it.value as HashMap<String, Any>)
            } else {
                it.value
            }
        }.toSortedMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun insertNested(files: HashMap<String, Any>, relativePath: String, sha: String) {
        val file = relativePath.substringAfterLast("/")
        val path = relativePath.substringBeforeLast("/")
        if (path == file) {
            files[file] = sha
        } else {
            val pathFirst = path.substringBefore("/")
            val pathFirstMap = if (files.containsKey(pathFirst) && files[pathFirst] is HashMap<*, *>) {
                files[pathFirst] as HashMap<String, Any>
            } else {
                val pathFirstMap = HashMap<String, Any>()
                files[pathFirst] = pathFirstMap
                pathFirstMap
            }
            insertNested(pathFirstMap, relativePath.substringAfter("/"), sha)
        }
    }

    /**
     * Sync all files
     *
     * @param config a yaml file that describe a set of files to fetch/sync from a given repository
     * @param verbose if true, it will print its operations to standard out.
     */
    fun sync(
        config: GitQueryConfig,
        verbose: Boolean = false,
    ) {
        this.verbose = verbose
        config.validate()

        val outputPath = toAbsolutePath(config.outputDir)

        val actualRepoPath = config.getActualRepoPath()

        prepareRepo(config.remote, config.branch, actualRepoPath)

        prepareOutputDirectory(
            outputPath = outputPath,
            cleanOutput = config.cleanOutput
        )

        // Sync all the files in `config.files`, recursively.
        syncFiles(
            fileMap = config.files,
            remote = config.remote,
            commits = config.commits,
            repoPath = actualRepoPath,
            outputPath = outputPath,
            relativePath = ""
        )
    }

    /**
     * Checks if [path] is already absolute (starts with /), if yes return it, if not, return
     * [prefixPath]/[path]. [prefixPath] defaults to System.getProperty("user.dir").
     * If the path is empty, a blank string is returned.
     */
    fun toAbsolutePath(
        path: String,
        prefixPath: String = System.getProperty("user.dir"),
    ): String {
        return when {
            path.isBlank() -> {
                ""
            }
            path[0] == '/' -> {
                path
            }
            else -> {
                "$prefixPath/$path"
            }
        }
    }

    /**
     * Clone or pull the `remote` repo @ `branch` into file(`repoDir`)
     *
     * @param remote the url to the remote git repo
     * @param branch a branch in the remote repo
     * @param repoDir where the remote repo(s) can be cloned locally and stored temporarily.
     */
    private fun prepareRepo(remote: String, branch: String, repoDir: String) {
        val repoExists = repoExists(repoDir)
        var exitCode = 0

        // If repo directory already exists, it means we have already cloned the remote repo
        if (repoExists) {
            // Since we have th repo already, fetch the right branch from origin and checkout the branch
            // In cases where the branch changes, `git checkout $branch` will fail silently, prompting
            // us to do a clean single branch clone of the repe.
            exitCode = sh(
                "cd $repoDir && git checkout $branch &>/dev/null && git pull origin $branch"
            )
        }

        // Either:
        // 1) repoDir doesn't exist
        // 2) or, we couldn't pull the branch that we wanted.
        // Cleanup and try a fresh clone.
        if (exitCode != 0 || !repoExists) {
            sh("rm -rf $repoDir")
            exitCode = sh("git clone --single-branch -b $branch $remote $repoDir")
        }

        check(exitCode == 0) { "Error cloning/updating repo $remote into directory $repoDir" }
    }

    /**
     * Sync all files recursively.
     */
    private fun syncFiles(
        fileMap: Map<String, Any>,
        commits: Map<String, String>,
        remote: String,
        repoPath: String,
        outputPath: String,
        relativePath: String,
    ) {
        fileMap.forEach { (filename, value) ->
            val path = if (relativePath.isNotBlank()) "$relativePath/$filename" else filename
            val destDir = "$outputPath/$path"

            // Value is a map of filenames and directory names to shas
            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    syncFiles(
                        commits = commits,
                        fileMap = fileMap[filename] as Map<String, Any>,
                        remote = remote,
                        repoPath = repoPath,
                        outputPath = outputPath,
                        relativePath = path
                    )
                }
                // Value is expected to be the git sha
                else -> {
                    var actualRelativePath = path.substringBeforeLast("/")
                    if (actualRelativePath == path) {
                        actualRelativePath = relativePath
                    }
                    val sha = if (commits.containsKey(value)) commits[value] else value
                    // Create the destination directory for each file
                    // `<outputDir>/<definition>/file.proto`,
                    // then run `git show sha:file > dest` to copy the file into the dest
                    val exitCode = sh(
                        """
                        (cd $repoPath && mkdir -p $outputPath/$actualRelativePath &&
                        git show $sha:$path > $destDir)
                        """.trimIndent()
                    )
                    check(exitCode == 0) {
                        "Failed to sync: $remote/$path: exit code=$exitCode"
                    }
                }
            }
        }
    }

    /**
     * Check for and create the output folder - relative to projectDir. Throws exceptions if there are issues.
     */
    private fun prepareOutputDirectory(outputPath: String, cleanOutput: Boolean) {
        println("GitQuery: creating outputPath: $outputPath")

        // Either outputPath exists or we can create it
        check(0 == sh("[ -d $outputPath ] || mkdir -p $outputPath")) {
            "OutputDir: $outputPath not found and couldn't be created"
        }

        // Either outputPath doesn't exist, or we can write to it.
        check(0 == sh("[ ! -d $outputPath ] || [ -w $outputPath ]")) {
            "OutputDir: $outputPath does not have write permission"
        }

        if (cleanOutput) {
            // Clean the output folder so that if files were removed from the config, they don't get included.
            check(0 == sh("(cd $outputPath && rm -rf *)")) {
                "Error cleaning outputDir with path: `$outputPath"
            }
        }
    }

    /**
     * Get current sha of the repoDir.
     */
    private fun repoSha(repoDir: String): String {
        return shellResult("(cd $repoDir && git rev-parse HEAD)")
    }

    /**
     * Checks the existence of the repoDir
     */
    private fun repoExists(repoDir: String): Boolean {
        return 0 == sh("[ -d $repoDir ] && [ -d $repoDir/.git ]")
    }

    /**
     * Run a shell command.
     */
    private fun sh(vararg cmd: String): Int = runCommand(*listOf("sh", "-c", *cmd).toTypedArray())

    private fun runCommand(vararg cmd: String): Int {
        val processBuilder = ProcessBuilder()
            .command(*cmd)
            .redirectErrorStream(true)

        if (verbose) {
            println(processBuilder.command().toString())
        }

        val process = processBuilder.start()
        val lines = process.inputStream.bufferedReader().use { reader ->
            reader.readLines().joinToString(separator = "\n")
        }

        val exitCode = process.waitFor()
        if (verbose || (exitCode != 0 && lines.isNotEmpty())) {
            println(processBuilder.command().toString())
            println("exit code = $exitCode")
            println(lines)
        }
        return exitCode
    }

    /**
     * Run a shell command for a value.
     */
    private fun shellResult(vararg cmd: String): String =
        runCommandAndGetResult(*listOf("sh", "-c", *cmd).toTypedArray())

    private fun runCommandAndGetResult(vararg cmd: String): String {
        val processBuilder = ProcessBuilder()
            .command(*cmd)
            .redirectErrorStream(false)

        if (verbose) {
            println(processBuilder.command().toString())
        }

        val process = processBuilder.start()
        val lines = process.inputStream.bufferedReader().use { reader ->
            reader.readLines().joinToString(separator = "\n")
        }

        val exitCode = process.waitFor()
        if ((exitCode != 0 && lines.isNotEmpty())) {
            println(processBuilder.command().toString())
            println("exit code = $exitCode")
            println(lines)
        }
        return lines
    }
}
