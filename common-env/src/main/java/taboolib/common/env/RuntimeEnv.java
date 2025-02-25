package taboolib.common.env;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import taboolib.common.ClassAppender;
import taboolib.common.PrimitiveIO;
import taboolib.common.PrimitiveSettings;
import taboolib.common.TabooLib;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

import static taboolib.common.PrimitiveSettings.KOTLINX_VERSION;
import static taboolib.common.PrimitiveSettings.KOTLIN_VERSION;

/**
 * TabooLib
 * taboolib.common.env.RuntimeEnv
 *
 * @author sky
 * @since 2021/6/15 6:23 下午
 */
public class RuntimeEnv {

    public static final String KOTLIN_ID = "!kotlin".substring(1);
    public static final String KOTLINX_ID = "!kotlinx.coroutines".substring(1);

    public static final RuntimeEnv ENV = new RuntimeEnv();

    private final String defaultAssets = PrimitiveSettings.FILE_ASSETS;
    private final String defaultLibrary = PrimitiveSettings.FILE_LIBS;
    private final String defaultRepositoryCentral = PrimitiveSettings.REPO_CENTRAL;

    static void init() throws Throwable {
        List<JarRelocation> rel = new ArrayList<>();
        // 在非隔离模式对 Kotlin 进行重定向
        if (!PrimitiveSettings.IS_ISOLATED_MODE) {
            // 其他 TabooLib 插件可能加载了相同版本的 Kotlin 环境，因此无需重复加载
            if (TabooLib.isKotlinEnvironment()) {
                return;
            }
            // 启用 Kotlin 重定向
            if (!PrimitiveSettings.SKIP_KOTLIN_RELOCATE) {
                rel.add(new JarRelocation(KOTLIN_ID + ".", KOTLIN_ID + KOTLIN_VERSION.replace(".", "") + "."));
                rel.add(new JarRelocation(KOTLINX_ID + ".", KOTLINX_ID + KOTLINX_VERSION.replace(".", "") + "."));
            }
        }
        // 加载 Kotlin 环境
        if (!KOTLIN_VERSION.equals("null")) ENV.loadDependency("org.jetbrains.kotlin:kotlin-stdlib:" + KOTLIN_VERSION, rel);
        if (!KOTLINX_VERSION.equals("null")) ENV.loadDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:" + KOTLINX_VERSION, false, rel);
    }

    public void inject(@NotNull Class<?> clazz) throws Throwable {
        loadAssets(clazz);
        loadDependency(clazz);
    }

    public void loadAssets(@NotNull Class<?> clazz) throws IOException {
        RuntimeResource[] resources = null;
        if (clazz.isAnnotationPresent(RuntimeResource.class)) {
            resources = clazz.getAnnotationsByType(RuntimeResource.class);
        } else {
            RuntimeResources annotation = clazz.getAnnotation(RuntimeResources.class);
            if (annotation != null) {
                resources = annotation.value();
            }
        }
        if (resources == null) {
            return;
        }
        for (RuntimeResource resource : resources) {
            loadAssets(resource.name(), resource.hash(), resource.value(), resource.zip());
        }
    }

    public void loadAssets(String name, String hash, String url, boolean zip) throws IOException {
        File file;
        if (name.isEmpty()) {
            file = new File(defaultAssets, hash.substring(0, 2) + "/" + hash);
        } else {
            file = new File(defaultAssets, name);
        }
        if (file.exists() && PrimitiveIO.getHash(file).equals(hash)) {
            return;
        }
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        PrimitiveIO.println("Downloading assets " + url.substring(url.lastIndexOf('/') + 1));
        if (zip) {
            File cacheFile = new File(file.getParentFile(), file.getName() + ".zip");
            PrimitiveIO.downloadFile(new URL(url + ".zip"), cacheFile);
            try (ZipFile zipFile = new ZipFile(cacheFile)) {
                InputStream inputStream = zipFile.getInputStream(zipFile.getEntry(url.substring(url.lastIndexOf('/') + 1)));
                try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                    fileOutputStream.write(PrimitiveIO.readFully(inputStream));
                }
            } finally {
                cacheFile.delete();
            }
        } else {
            PrimitiveIO.downloadFile(new URL(url), file);
        }
    }

    private boolean test(String path) {
        String test = path.startsWith("!") ? path.substring(1) : path;
        return !test.isEmpty() && ClassAppender.isExists(test);
    }

    public void loadDependency(@NotNull Class<?> clazz) throws Throwable {
        File baseFile = new File(defaultLibrary);
        RuntimeDependency[] dependencies = null;
        if (clazz.isAnnotationPresent(RuntimeDependency.class)) {
            dependencies = clazz.getAnnotationsByType(RuntimeDependency.class);
        } else {
            RuntimeDependencies annotation = clazz.getAnnotation(RuntimeDependencies.class);
            if (annotation != null) {
                dependencies = annotation.value();
            }
        }
        if (dependencies != null) {
            for (RuntimeDependency dependency : dependencies) {
                String allTest = dependency.test();
                List<String> tests = new ArrayList<>();
                if (allTest.contains(",")) {
                    tests.addAll(Arrays.asList(allTest.split(",")));
                } else {
                    tests.add(allTest);
                }
                if (tests.stream().allMatch(this::test)) {
                    continue;
                }
                List<JarRelocation> relocation = new ArrayList<>();
                String[] relocate = dependency.relocate();
                if (relocate.length % 2 != 0) {
                    throw new IllegalStateException("invalid relocate format");
                }
                for (int i = 0; i + 1 < relocate.length; i += 2) {
                    String pattern = relocate[i].startsWith("!") ? relocate[i].substring(1) : relocate[i];
                    String relocatePattern = relocate[i + 1].startsWith("!") ? relocate[i + 1].substring(1) : relocate[i + 1];
                    relocation.add(new JarRelocation(pattern, relocatePattern));
                }
                String url = dependency.value().startsWith("!") ? dependency.value().substring(1) : dependency.value();
                loadDependency(url, baseFile, relocation, dependency.repository(), dependency.ignoreOptional(), dependency.ignoreException(), dependency.transitive(), dependency.scopes());
            }
        }
    }

    public void loadDependency(@NotNull String url) throws Throwable {
        loadDependency(url, new File(defaultLibrary));
    }

    public void loadDependency(@NotNull String url, @Nullable String repository) throws Throwable {
        loadDependency(url, new File(defaultLibrary), repository);
    }

    public void loadDependency(@NotNull String url, @NotNull List<JarRelocation> relocation) throws Throwable {
        loadDependency(url, new File(defaultLibrary), relocation, null, true, false, true, new DependencyScope[]{DependencyScope.RUNTIME, DependencyScope.COMPILE});
    }

    public void loadDependency(@NotNull String url, boolean transitive, @NotNull List<JarRelocation> relocation) throws Throwable {
        loadDependency(url, new File(defaultLibrary), relocation, null, true, false, transitive, new DependencyScope[]{DependencyScope.RUNTIME, DependencyScope.COMPILE});
    }

    public void loadDependency(@NotNull String url, @NotNull File baseDir) throws Throwable {
        loadDependency(url, baseDir, null);
    }

    public void loadDependency(@NotNull String url, @NotNull File baseDir, @Nullable String repository) throws Throwable {
        loadDependency(url, baseDir, new ArrayList<>(), repository, true, false, true, new DependencyScope[]{DependencyScope.RUNTIME, DependencyScope.COMPILE});
    }

    public void loadDependency(
            @NotNull String url,
            @NotNull File baseDir,
            @NotNull List<JarRelocation> relocation,
            @Nullable String repository,
            boolean ignoreOptional,
            boolean ignoreException,
            boolean transitive,
            @NotNull DependencyScope[] dependencyScopes
    ) throws Throwable {
        String[] args = url.split(":");
        DependencyDownloader downloader = new DependencyDownloader(baseDir, relocation);
        // 支持用户对源进行替换
        if (repository == null || repository.isEmpty()) {
            repository = defaultRepositoryCentral;
        } else if (PrimitiveSettings.RUNTIME_PROPERTIES.containsKey("repo-" + repository)) {
            repository = PrimitiveSettings.RUNTIME_PROPERTIES.getProperty("repo-" + repository);
        }
        downloader.addRepository(new Repository(repository));
        downloader.setIgnoreOptional(ignoreOptional);
        downloader.setIgnoreException(ignoreException);
        downloader.setDependencyScopes(dependencyScopes);
        downloader.setTransitive(transitive);
        // 解析依赖
        File pomFile = new File(baseDir, String.format("%s/%s/%s/%s-%s.pom", args[0].replace('.', '/'), args[1], args[2], args[1], args[2]));
        File pomFile1 = new File(pomFile.getPath() + ".sha1");
        // 验证文件完整性
        if (PrimitiveIO.validation(pomFile, pomFile1)) {
            downloader.loadDependencyFromInputStream(pomFile.toPath().toUri().toURL().openStream());
        } else {
            String pom = String.format("%s/%s/%s/%s/%s-%s.pom", repository, args[0].replace('.', '/'), args[1], args[2], args[1], args[2]);
            PrimitiveIO.println("Downloading library %s:%s:%s %s", args[0], args[1], args[2], transitive ? "(transitive)" : "");
            downloader.loadDependencyFromInputStream(new URL(pom).openStream());
        }
        // 加载自身
        Dependency current = new Dependency(args[0], args[1], args[2], DependencyScope.RUNTIME);
        if (transitive) {
            downloader.injectClasspath(downloader.loadDependency(downloader.getRepositories(), current));
        } else {
            downloader.injectClasspath(Collections.singleton(current));
        }
    }
}
