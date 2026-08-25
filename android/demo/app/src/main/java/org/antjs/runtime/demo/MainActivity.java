package org.antjs.runtime.demo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.antjs.runtime.StorageLocation;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small phone/TV IDE host for the Ant Android embedding.
 *
 * <p>The important invariant is that a selected SAF tree remains a SAF tree
 * for editing, dependency installation, cache access and execution. This
 * activity never resolves a content URI to a guessed POSIX path and never
 * creates a private mirror of a selected project.</p>
 */
public final class MainActivity extends Activity implements AntBackendController.Listener {
    private static final String PREFS = "ant-api-demo";
    private static final String PROJECT_KIND = "project-kind";
    private static final String PROJECT_LOCATION = "project-location";
    private static final String CACHE_KIND = "cache-kind";
    private static final String CACHE_LOCATION = "cache-location";
    private static final String LEGACY_TREE = "selected-tree";
    private static final String LEGACY_CACHE_TREE = "selected-cache-tree";
    private static final int REQUEST_PROJECT_TREE = 1001;
    private static final int REQUEST_CACHE_TREE = 1002;
    private static final int REQUEST_NOTIFICATIONS = 1003;
    private static final int REQUEST_LEGACY_STORAGE = 1004;
    private static final String ALL_FILES_PROMPTED = "all-files-prompted";
    private static final String PUBLIC_ROOT_NAME = "FAnt";

    private static final int BG = Color.rgb(245, 247, 250);
    private static final int TEXT = Color.rgb(42, 52, 65);
    private static final int MUTED = Color.rgb(103, 116, 132);
    private static final int BLUE = Color.rgb(27, 119, 184);

    private AntBackendController backend;
    private SharedPreferences preferences;
    private ExecutorService ioExecutor;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private int searchGeneration;

    private StorageLocation projectLocation;
    private StorageLocation cacheLocation;
    private String serverSource = "";
    private String packageJsonSource = "";
    private String activeFile = "server.ts";
    private String selectedDependencyName;
    private int loadGeneration;
    private boolean workspaceReady;
    private boolean busy;
    private boolean serverRunning;
    private boolean requestStartAfterNotification;
    private boolean waitingForAllFilesSettings;
    private boolean waitingForLegacyStoragePermission;

    private TextView status;
    private TextView endpoint;
    private TextView output;
    private ScrollView outputScroll;
    private TextView projectLabel;
    private TextView cacheLabel;
    private TextView locationDetails;
    private LinearLayout dependencyList;
    private EditText codeEditor;
    private FocusButton runtimeTab;
    private FocusButton projectTab;
    private FocusButton start;
    private FocusButton stop;
    private FocusButton health;
    private FocusButton format;
    private FocusButton clearLogs;
    private FocusButton serverFileTab;
    private FocusButton packageFileTab;
    private FocusButton save;
    private FocusButton reset;
    private FocusButton openDirectorySettings;
    private FocusButton openDependencyManager;
    private FocusButton addDependency;
    private FocusButton removeDependency;
    private FocusButton chooseProject;
    private FocusButton chooseCache;
    private FocusButton clearProject;
    private FocusButton clearCache;
    private FocusButton requestAllFiles;
    private FocusButton directoryBack;
    private FocusButton dependencyBack;
    private View runtimePage;
    private View projectPage;
    private View projectMainPage;
    private View directoryPage;
    private View dependencyPage;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        ioExecutor = Executors.newSingleThreadExecutor();
        backend = AntBackendController.shared(getApplicationContext());
        backend.setListener(this);
        serverRunning = backend.isReady();
        configureSystemBars();
        loadLocations();
        setContentView(buildRoot());
        bindActions();
        showProjectSubpage("main");
        updateLocationLabels();
        loadProjectAsync();
        updateControls();
        requestAllFilesAccessIfNeeded();
    }

    @Override
    protected void onDestroy() {
        if (ioExecutor != null) ioExecutor.shutdownNow();
        if (backend != null) backend.clearListener(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForAllFilesSettings) {
            waitingForAllFilesSettings = false;
            if (hasAllFilesAccess()) {
                applyPublicDefaultsIfNeeded();
                onLog("已授予完全文件访问权限，可直接使用公共存储绝对路径。");
            } else if (projectLocation == null) {
                setStatus("未授予完全文件访问权限，请选择 SAF 目录。");
            }
            updateLocationLabels();
            updateControls();
            loadProjectAsync();
        }
        if (backend == null) return;
        serverRunning = backend.isReady();
        updateRuntimeText();
        updateControls();
    }

    private void configureSystemBars() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void loadLocations() {
        projectLocation = readStoredLocation(PROJECT_KIND, PROJECT_LOCATION, LEGACY_TREE);
        cacheLocation = readStoredLocation(CACHE_KIND, CACHE_LOCATION, LEGACY_CACHE_TREE);
        if (!hasAllFilesAccess()) {
            // A legacy demo install may have persisted its old private fallback.
            // Do not silently keep using it: the user must choose SAF explicitly.
            if (isAppPrivateFallback(projectLocation)) projectLocation = null;
            if (isAppPrivateFallback(cacheLocation)) cacheLocation = null;
        }
        applyPublicDefaultsIfNeeded();
        backend.setLocations(projectLocation, cacheLocation);
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        // Android 10 scoped storage cannot be treated as an unrestricted path
        // with this target SDK; SAF is the explicit fallback for API 29.
        return false;
    }

    private File publicStorageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    private StorageLocation defaultPublicProject() {
        return StorageLocation.filePath(new File(publicStorageRoot(),
                PUBLIC_ROOT_NAME + "/project"));
    }

    private StorageLocation defaultPublicCache() {
        return StorageLocation.filePath(new File(publicStorageRoot(),
                PUBLIC_ROOT_NAME + "/cache"));
    }

    private boolean isAppPrivateFallback(StorageLocation location) {
        if (location == null || !location.isFilePath()) return false;
        String path = location.value();
        String privateRoot = getNoBackupFilesDir().getAbsolutePath();
        return path.equals(privateRoot + "/ant-api-demo")
                || path.startsWith(privateRoot + "/ant-api-demo/");
    }

    private void applyPublicDefaultsIfNeeded() {
        if (!hasAllFilesAccess()) return;
        boolean changed = false;
        if (projectLocation == null || isAppPrivateFallback(projectLocation)) {
            projectLocation = defaultPublicProject();
            changed = true;
        }
        if (cacheLocation == null || isAppPrivateFallback(cacheLocation)) {
            cacheLocation = defaultPublicCache();
            changed = true;
        }
        if (changed && preferences != null) persistLocations();
    }

    private void requestAllFilesAccessIfNeeded() {
        if (hasAllFilesAccess()) return;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                waitingForLegacyStoragePermission = true;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_LEGACY_STORAGE);
            }
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (preferences.getBoolean(ALL_FILES_PROMPTED, false)) return;
        preferences.edit().putBoolean(ALL_FILES_PROMPTED, true).apply();
        try {
            waitingForAllFilesSettings = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            setStatus("请在系统设置中允许 FAnt 使用所有文件，然后返回应用。");
        } catch (RuntimeException error) {
            onLog("无法打开完全文件访问设置：" + error.getMessage());
            setStatus("无法申请完全文件访问权限，请使用 SAF 目录。");
        }
    }

    private void openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            requestAllFilesAccessIfNeeded();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            setStatus("当前 Android 版本不需要单独申请完全文件访问权限。");
            return;
        }
        try {
            waitingForAllFilesSettings = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (RuntimeException error) {
            onLog("无法打开完全文件访问设置：" + error.getMessage());
            setStatus("请在系统设置中手动授予文件访问权限。");
        }
    }

    private StorageLocation readStoredLocation(String kindKey, String valueKey, String legacyKey) {
        int kind = preferences.getInt(kindKey, -1);
        String value = preferences.getString(valueKey, null);
        if (value == null) value = preferences.getString(legacyKey, null);
        if (kind < 0 && value != null) kind = value.startsWith("content://")
                ? StorageLocation.Kind.SAF_TREE.ordinal() : StorageLocation.Kind.FILE_PATH.ordinal();
        if (value == null || (kind != 0 && kind != 1)) return null;
        try {
            return kind == StorageLocation.Kind.SAF_TREE.ordinal()
                    ? StorageLocation.safTree(value) : StorageLocation.filePath(value);
        } catch (RuntimeException error) {
            onLog("已忽略无效的存储位置：" + value);
            return null;
        }
    }

    private void persistLocations() {
        SharedPreferences.Editor editor = preferences.edit();
        if (projectLocation == null) {
            editor.remove(PROJECT_KIND).remove(PROJECT_LOCATION);
        } else {
            editor.putInt(PROJECT_KIND, projectLocation.kind().ordinal())
                    .putString(PROJECT_LOCATION, projectLocation.value());
        }
        if (cacheLocation == null) {
            editor.remove(CACHE_KIND).remove(CACHE_LOCATION);
        } else {
            editor.putInt(CACHE_KIND, cacheLocation.kind().ordinal())
                    .putString(CACHE_LOCATION, cacheLocation.value());
        }
        editor.apply();
        backend.setLocations(projectLocation, cacheLocation);
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(10), dp(16), 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("FAnt 后端工作台", 20, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView version = label("JS / TS Runtime", 11, MUTED);
        header.addView(version, wrap());
        root.addView(header, match());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackground(round(Color.WHITE, dp(6)));
        tabs.setClipToPadding(false);
        runtimeTab = tab("运行");
        projectTab = tab("项目");
        tabs.addView(runtimeTab, weightWithMargin(dp(4)));
        tabs.addView(projectTab, weight());
        root.addView(tabs, match());

        FrameLayout content = new FrameLayout(this);
        runtimePage = buildRuntimePage();
        projectPage = buildProjectPage();
        content.addView(runtimePage, frame());
        content.addView(projectPage, frame());
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        showPage("runtime");
        applySystemBarInsets(root);
        return root;
    }

    /** Keeps app content clear of status/navigation bars on Android 15 and OEM edge-to-edge modes. */
    private void applySystemBarInsets(View root) {
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(baseLeft + left, baseTop + top,
                    baseRight + right, baseBottom + bottom);
            return insets;
        });
        root.post(root::requestApplyInsets);
    }

    private View buildRuntimePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(2), 0, 0);

        LinearLayout controls = new LinearLayout(this);
        start = action("启动", FocusButton.STYLE_PRIMARY);
        stop = action("停止", FocusButton.STYLE_DANGER);
        controls.addView(start, weightWithMargin(dp(6)));
        controls.addView(stop, weight());
        page.addView(controls, match());

        endpoint = label("服务未启动", 12, MUTED);
        endpoint.setPadding(dp(4), dp(2), dp(4), dp(2));
        endpoint.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        endpoint.setSingleLine(true);
        page.addView(endpoint, match());

        LinearLayout tests = new LinearLayout(this);
        health = action("健康检查", FocusButton.STYLE_SECONDARY);
        format = action("格式化示例", FocusButton.STYLE_SECONDARY);
        tests.addView(health, weightWithMargin(dp(6)));
        tests.addView(format, weight());
        page.addView(tests, match());

        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = label("输出日志", 14, TEXT);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logHeader.addView(logTitle, new LinearLayout.LayoutParams(0, dp(30), 1f));
        clearLogs = action("清空", FocusButton.STYLE_TEXT);
        logHeader.addView(clearLogs, wrap());
        page.addView(logHeader, match());

        outputScroll = new ScrollView(this);
        outputScroll.setFillViewport(true);
        outputScroll.setVerticalScrollBarEnabled(true);
        output = label("", 12, Color.rgb(46, 57, 70));
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setGravity(Gravity.TOP | Gravity.START);
        output.setPadding(dp(12), dp(12), dp(12), dp(12));
        output.setBackgroundColor(Color.TRANSPARENT);
        outputScroll.setBackground(round(Color.WHITE, dp(8)));
        outputScroll.addView(output, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(outputScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private View buildProjectPage() {
        FrameLayout container = new FrameLayout(this);
        projectMainPage = buildProjectMainPage();
        directoryPage = buildDirectoryPage();
        dependencyPage = buildDependencyPage();
        container.addView(projectMainPage, frame());
        container.addView(directoryPage, frame());
        container.addView(dependencyPage, frame());
        return container;
    }

    private View buildProjectMainPage() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(6));

        LinearLayout fileTabs = new LinearLayout(this);
        serverFileTab = tab("server.ts");
        packageFileTab = tab("package.json");
        fileTabs.addView(serverFileTab, weightWithMargin(dp(4)));
        fileTabs.addView(packageFileTab, weight());
        content.addView(fileTabs, match());

        codeEditor = new EditText(this);
        codeEditor.setTextColor(TEXT);
        codeEditor.setHintTextColor(MUTED);
        codeEditor.setHint("编辑当前文件");
        codeEditor.setGravity(Gravity.TOP | Gravity.START);
        codeEditor.setTypeface(Typeface.MONOSPACE);
        codeEditor.setTextSize(13);
        codeEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeEditor.setSingleLine(false);
        codeEditor.setHorizontallyScrolling(false);
        codeEditor.setPadding(dp(12), dp(12), dp(12), dp(12));
        codeEditor.setBackground(round(Color.WHITE, dp(8)));
        ScrollView editorScroll = new ScrollView(this);
        editorScroll.setFillViewport(true);
        editorScroll.addView(codeEditor, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));
        content.addView(editorScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        openDirectorySettings = action("目录设置", FocusButton.STYLE_SECONDARY);
        openDependencyManager = action("依赖管理", FocusButton.STYLE_SECONDARY);
        reset = action("重置", FocusButton.STYLE_SECONDARY);
        save = action("保存", FocusButton.STYLE_PRIMARY);
        actions.addView(openDirectorySettings, weightWithMargin(dp(4)));
        actions.addView(openDependencyManager, weightWithMargin(dp(4)));
        actions.addView(reset, weightWithMargin(dp(4)));
        actions.addView(save, weight());
        content.addView(actions, match());
        return content;
    }

    private View buildDirectoryPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = pageContent();
        directoryBack = action("返回项目", FocusButton.STYLE_TEXT);
        content.addView(subpageHeader("目录设置", directoryBack), match());

        requestAllFiles = action("申请全部文件权限", FocusButton.STYLE_TEXT);
        content.addView(requestAllFiles, alignEnd());

        LinearLayout projectRow = new LinearLayout(this);
        projectRow.setGravity(Gravity.CENTER_VERTICAL);
        projectLabel = label("", 12, TEXT);
        projectLabel.setMaxLines(5);
        projectLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        chooseProject = action("源码目录", FocusButton.STYLE_SECONDARY);
        projectRow.addView(projectLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        projectRow.addView(chooseProject, wrap());
        content.addView(projectRow, match());
        clearProject = action("使用默认项目", FocusButton.STYLE_TEXT);
        content.addView(clearProject, alignEnd());

        LinearLayout cacheRow = new LinearLayout(this);
        cacheRow.setGravity(Gravity.CENTER_VERTICAL);
        cacheLabel = label("", 12, TEXT);
        cacheLabel.setMaxLines(5);
        cacheLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        chooseCache = action("缓存目录", FocusButton.STYLE_SECONDARY);
        cacheRow.addView(cacheLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cacheRow.addView(chooseCache, wrap());
        content.addView(cacheRow, match());
        clearCache = action("使用项目内缓存", FocusButton.STYLE_TEXT);
        content.addView(clearCache, alignEnd());

        locationDetails = label("", 11, MUTED);
        locationDetails.setTypeface(Typeface.MONOSPACE);
        locationDetails.setPadding(dp(2), dp(15), dp(2), 0);
        content.addView(locationDetails, match());
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View buildDependencyPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = pageContent();
        dependencyBack = action("返回项目", FocusButton.STYLE_TEXT);
        content.addView(subpageHeader("依赖管理", dependencyBack), match());
        LinearLayout dependencyHeader = new LinearLayout(this);
        dependencyHeader.setGravity(Gravity.CENTER_VERTICAL);
        dependencyHeader.setPadding(dp(10), 0, dp(10), 0);
        dependencyHeader.setBackground(round(Color.rgb(238, 241, 245), dp(4)));
        TextView packageHeader = label("软件包", 13, TEXT);
        packageHeader.setTypeface(Typeface.DEFAULT_BOLD);
        dependencyHeader.addView(packageHeader, new LinearLayout.LayoutParams(0, dp(38), 1f));
        TextView versionHeader = label("版本", 13, TEXT);
        versionHeader.setTypeface(Typeface.DEFAULT_BOLD);
        dependencyHeader.addView(versionHeader, new LinearLayout.LayoutParams(dp(125), dp(38)));
        content.addView(dependencyHeader, match());
        dependencyList = new LinearLayout(this);
        dependencyList.setOrientation(LinearLayout.VERTICAL);
        content.addView(dependencyList, match());
        LinearLayout actions = new LinearLayout(this);
        addDependency = action("添加", FocusButton.STYLE_PRIMARY);
        removeDependency = action("删除选中", FocusButton.STYLE_DANGER);
        actions.addView(addDependency, weightWithMargin(dp(6)));
        actions.addView(removeDependency, weight());
        content.addView(actions, match());
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout subpageHeader(String title, FocusButton back) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(back, wrap());
        TextView text = label(title, 18, TEXT);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setPadding(dp(10), 0, 0, 0);
        header.addView(text, new LinearLayout.LayoutParams(0, dp(48), 1f));
        return header;
    }

    private void bindActions() {
        runtimeTab.setOnClickListener(view -> showPage("runtime"));
        projectTab.setOnClickListener(view -> showPage("project"));
        start.setOnClickListener(view -> startBackend());
        stop.setOnClickListener(view -> {
            busy = true;
            updateControls();
            BackendService.stop(this);
        });
        health.setOnClickListener(view -> backend.request("/api/health"));
        format.setOnClickListener(view -> backend.request(
                "/api/format?text=Hello%20Android%20TV%20from%20npm"));
        clearLogs.setOnClickListener(view -> output.setText(""));
        serverFileTab.setOnClickListener(view -> showFile("server.ts"));
        packageFileTab.setOnClickListener(view -> showFile("package.json"));
        save.setOnClickListener(view -> saveProject(false, false));
        reset.setOnClickListener(view -> confirmReset());
        openDirectorySettings.setOnClickListener(view -> showProjectSubpage("directory"));
        openDependencyManager.setOnClickListener(view -> showProjectSubpage("dependencies"));
        directoryBack.setOnClickListener(view -> showProjectSubpage("main"));
        dependencyBack.setOnClickListener(view -> showProjectSubpage("main"));
        requestAllFiles.setOnClickListener(view -> openAllFilesAccessSettings());
        chooseProject.setOnClickListener(view -> openLocationPicker(REQUEST_PROJECT_TREE,
                projectLocation));
        chooseCache.setOnClickListener(view -> openLocationPicker(REQUEST_CACHE_TREE,
                cacheLocation));
        clearProject.setOnClickListener(view -> {
            projectLocation = hasAllFilesAccess() ? defaultPublicProject() : null;
            persistLocations();
            loadProjectAsync();
        });
        clearCache.setOnClickListener(view -> {
            cacheLocation = null;
            persistLocations();
            updateLocationLabels();
        });
        addDependency.setOnClickListener(view -> openDependencyDialog());
        removeDependency.setOnClickListener(view -> removeSelectedDependency());
    }

    private Uri uriOf(StorageLocation location) {
        return location != null && location.isSafTree() ? Uri.parse(location.value()) : null;
    }

    private void openLocationPicker(int requestCode, StorageLocation current) {
        if (hasAllFilesAccess()) {
            new AlertDialog.Builder(this)
                    .setTitle("选择存储方式")
                    .setItems(new String[]{"公共绝对路径", "SAF 目录"}, (dialog, which) -> {
                        if (which == 0) openFilePathPicker(requestCode,
                                current != null && current.isFilePath()
                                        ? new File(current.value()) : publicStorageRoot());
                        else openTreePicker(requestCode, uriOf(current));
                    }).show();
            return;
        }
        openTreePicker(requestCode, uriOf(current));
    }

    /** Browses the real shared-storage tree only when MANAGE_EXTERNAL_STORAGE is granted. */
    private void openFilePathPicker(int requestCode, File initial) {
        final File root;
        try {
            root = publicStorageRoot().getCanonicalFile();
        } catch (IOException error) {
            setStatus("无法读取公共存储路径：" + error.getMessage());
            return;
        }
        File startingDirectory = root;
        if (initial != null) {
            try {
                File candidate = initial.getCanonicalFile();
                String rootPath = root.getPath();
                String candidatePath = candidate.getPath();
                if (candidatePath.equals(rootPath)
                        || candidatePath.startsWith(rootPath + File.separator)) {
                    startingDirectory = candidate.isDirectory() ? candidate : candidate.getParentFile();
                }
            } catch (IOException ignored) {
            }
        }
        final File[] current = new File[]{startingDirectory == null ? root : startingDirectory};
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(8), 0, dp(8), 0);
        final TextView path = label("", 11, MUTED);
        path.setTypeface(Typeface.MONOSPACE);
        path.setMaxLines(2);
        path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        path.setPadding(dp(2), dp(4), dp(2), dp(8));
        body.addView(path, match());
        final ScrollView listingScroll = new ScrollView(this);
        final LinearLayout listing = new LinearLayout(this);
        listing.setOrientation(LinearLayout.VERTICAL);
        listingScroll.addView(listing, new ScrollView.LayoutParams(-1, -2));
        body.addView(listingScroll, new LinearLayout.LayoutParams(-1, dp(300)));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(requestCode == REQUEST_PROJECT_TREE ? "选择源码目录" : "选择缓存目录")
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("选择此目录", null)
                .create();
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            File directory = current[0];
            if (directory == null || !directory.isDirectory()) directory = root;
            current[0] = directory;
            final File displayedDirectory = directory;
            path.setText(directory.getPath());
            listing.removeAllViews();
            if (!directory.equals(root)) {
                TextView parent = label("‹  上级目录", 14, BLUE);
                parent.setGravity(Gravity.CENTER_VERTICAL);
                parent.setPadding(dp(10), 0, dp(10), 0);
                parent.setMinHeight(dp(42));
                parent.setFocusable(true);
                parent.setClickable(true);
                parent.setBackground(round(Color.WHITE, dp(6)));
                parent.setOnClickListener(view -> {
                    File next = displayedDirectory.getParentFile();
                    if (next != null) {
                        current[0] = next;
                        render[0].run();
                    }
                });
                listing.addView(parent, match());
            }
            File[] children = directory.listFiles(file -> file.isDirectory() && file.canRead());
            if (children == null || children.length == 0) {
                TextView empty = label("没有可浏览的子目录。", 13, MUTED);
                empty.setPadding(dp(10), dp(14), dp(10), dp(14));
                listing.addView(empty, match());
                return;
            }
            Arrays.sort(children, (left, right) -> left.getName()
                    .compareToIgnoreCase(right.getName()));
            for (File child : children) {
                TextView row = label("▸  " + child.getName(), 14, TEXT);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), 0, dp(10), 0);
                row.setMinHeight(dp(42));
                row.setFocusable(true);
                row.setClickable(true);
                row.setBackground(round(Color.WHITE, dp(6)));
                row.setOnClickListener(view -> {
                    current[0] = child;
                    render[0].run();
                });
                LinearLayout.LayoutParams params = match();
                params.bottomMargin = dp(4);
                listing.addView(row, params);
            }
        };
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (!hasAllFilesAccess()) {
                    dialog.dismiss();
                    setStatus("完全文件访问权限已撤销，请重新授权或使用 SAF。");
                    return;
                }
                try {
                    StorageLocation selected = StorageLocation.filePath(current[0].getCanonicalFile());
                    if (requestCode == REQUEST_PROJECT_TREE) {
                        projectLocation = selected;
                        persistLocations();
                        loadProjectAsync();
                    } else {
                        cacheLocation = selected;
                        persistLocations();
                        updateLocationLabels();
                    }
                    onLog("已选择 FILE_PATH：" + selected.value());
                    setStatus("目录已设置为公共存储绝对路径。");
                    dialog.dismiss();
                } catch (IOException | RuntimeException error) {
                    setStatus("目录选择失败：" + error.getMessage());
                }
            });
        });
        render[0].run();
        dialog.show();
    }

    private void startBackend() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestStartAfterNotification = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        startBackendAfterPermission();
    }

    private void startBackendAfterPermission() {
        if (!workspaceReady || projectLocation == null) {
            setStatus("项目仍在加载，请稍候。");
            return;
        }
        saveProject(true, false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_LEGACY_STORAGE && waitingForLegacyStoragePermission) {
            waitingForLegacyStoragePermission = false;
            if (hasAllFilesAccess()) {
                applyPublicDefaultsIfNeeded();
                onLog("已授予公共存储访问权限，可使用 FILE_PATH。");
                loadProjectAsync();
            } else {
                setStatus("未授予公共存储权限，请选择 SAF 目录。");
            }
            updateLocationLabels();
            updateControls();
            return;
        }
        if (requestCode != REQUEST_NOTIFICATIONS || !requestStartAfterNotification) return;
        requestStartAfterNotification = false;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            onLog("未授予通知权限，系统可能隐藏前台服务通知。");
        }
        startBackendAfterPermission();
    }

    private void saveProject(boolean startAfterSave, boolean installAfterSave) {
        if (!workspaceReady || projectLocation == null) {
            setStatus("项目仍在加载，请稍候。");
            return;
        }
        if (serverRunning || backend.isStartingOrReady()) {
            setStatus("请先停止服务再编辑或安装依赖。");
            return;
        }
        captureEditor();
        if (!validPackageJson()) return;
        busy = true;
        setStatus(startAfterSave ? "正在保存并启动…" : "正在保存项目…");
        updateControls();
        final int generation = loadGeneration;
        final StorageLocation project = projectLocation;
        final String server = serverSource;
        final String packageJson = packageJsonSource;
        ioExecutor.execute(() -> {
            try {
                StorageFiles.writeText(this, project, "server.ts", server);
                StorageFiles.writeText(this, project, "package.json", packageJson);
                runOnUiThread(() -> {
                    if (generation != loadGeneration) return;
                    onLog("已保存 server.ts 和 package.json。");
                    busy = false;
                    updateControls();
                    if (startAfterSave) {
                        setStatus("正在启动 FAnt 后端…");
                        BackendService.startForProject(MainActivity.this,
                                projectLocation, cacheLocation);
                    } else if (installAfterSave) {
                        setStatus("正在安装依赖…");
                        backend.setLocations(projectLocation, cacheLocation);
                        backend.installDependencies(projectLocation);
                    } else {
                        setStatus("项目文件已保存。");
                    }
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("保存失败：" + error.getMessage());
                    onLog("保存失败：" + error);
                    updateControls();
                });
            }
        });
    }

    private boolean validPackageJson() {
        try {
            new JSONObject(packageJsonSource);
            return true;
        } catch (Exception error) {
            setStatus("package.json 不是有效 JSON：" + error.getMessage());
            showFile("package.json");
            return false;
        }
    }

    private void confirmReset() {
        if (!workspaceReady || busy || serverRunning) {
            setStatus("请先停止服务并等待项目加载完成。");
            return;
        }
        new AlertDialog.Builder(this).setTitle("重置示例")
                .setMessage("将直接覆盖所选项目中的 server.ts 和 package.json。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (dialog, which) -> resetSample()).show();
    }

    private void resetSample() {
        final int generation = loadGeneration;
        busy = true;
        setStatus("正在重置示例…");
        updateControls();
        ioExecutor.execute(() -> {
            try {
                String server = readAsset("backend/server.ts");
                String packageJson = readAsset("backend/package.json");
                StorageFiles.writeText(this, projectLocation, "server.ts", server);
                StorageFiles.writeText(this, projectLocation, "package.json", packageJson);
                runOnUiThread(() -> {
                    if (generation != loadGeneration) return;
                    serverSource = server;
                    packageJsonSource = packageJson;
                    displayFile(activeFile);
                    busy = false;
                    setStatus("示例已重置。");
                    onLog("已重置所选项目中的示例文件。");
                    updateControls();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("重置失败：" + error.getMessage());
                    onLog("重置失败：" + error);
                    updateControls();
                });
            }
        });
    }

    private void loadProjectAsync() {
        final int generation = ++loadGeneration;
        final StorageLocation location = projectLocation;
        workspaceReady = false;
        updateLocationLabels();
        if (location == null) {
            setStatus("请先设置源码目录：选择公共路径或 SAF 目录。");
            updateControls();
            return;
        }
        setStatus("正在加载项目…");
        updateControls();
        ioExecutor.execute(() -> {
            try {
                StorageFiles.mkdirs(this, location, "");
                StorageFiles.ensureAsset(this, location, "backend/package.json", "package.json");
                StorageFiles.ensureAsset(this, location, "backend/server.ts", "server.ts");
                String server = StorageFiles.readText(this, location, "server.ts");
                String packageJson = StorageFiles.readText(this, location, "package.json");
                runOnUiThread(() -> {
                    if (generation != loadGeneration) return;
                    serverSource = server;
                    packageJsonSource = packageJson;
                    workspaceReady = true;
                    displayFile(activeFile);
                    setStatus(serverRunning ? "后端正在运行" : "项目已就绪。");
                    updateLocationLabels();
                    updateControls();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration) return;
                    workspaceReady = false;
                    setStatus("项目加载失败：" + error.getMessage());
                    onLog("存储错误：" + error);
                    updateControls();
                });
            }
        });
    }

    private String readAsset(String name) throws IOException {
        InputStream input = getAssets().open(name);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private void openTreePicker(int requestCode, Uri initial) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initial != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        }
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_PROJECT_TREE && requestCode != REQUEST_CACHE_TREE)
                || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                throw new SecurityException("选择器没有授予写权限");
            }
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException error) {
            onLog("无法持久保存 SAF 权限：" + error.getMessage());
            setStatus("目录没有持久读写权限。");
            return;
        }
        if (requestCode == REQUEST_PROJECT_TREE) projectLocation = StorageLocation.safTree(uri.toString());
        else cacheLocation = StorageLocation.safTree(uri.toString());
        persistLocations();
        updateLocationLabels();
        if (requestCode == REQUEST_PROJECT_TREE) loadProjectAsync();
    }

    private void updateLocationLabels() {
        if (projectLabel == null) return;
        projectLabel.setText("项目目录\n" + StorageFiles.display(projectLocation));
        cacheLabel.setText(cacheLocation == null
                ? "依赖缓存目录\n项目目录/.ant/pkg-cache（项目内）"
                : "依赖缓存目录\n" + StorageFiles.display(cacheLocation));
        String projectPath = projectLocation == null ? "未设置" : projectLocation.value();
        String nodeModules = projectLocation == null ? "未设置"
                : projectLocation.kind() == StorageLocation.Kind.FILE_PATH
                ? projectLocation.value() + "/node_modules" : projectLocation.value() + "/node_modules";
        String cache = cacheLocation == null ? projectPath + "/.ant/pkg-cache" : cacheLocation.value();
        locationDetails.setText("项目：" + projectPath
                + "\nnode_modules：" + nodeModules
                + "\n依赖缓存：" + cache
                + "\n\nSAF_TREE 会直接由 Storage Bridge 读写，不生成私有副本。");
    }

    private void showFile(String file) {
        captureEditor();
        displayFile(file);
    }

    private void displayFile(String file) {
        activeFile = file;
        if (codeEditor != null) {
            codeEditor.setText("package.json".equals(file) ? packageJsonSource : serverSource);
            codeEditor.setSelection(0);
        }
        if (serverFileTab != null) serverFileTab.setSelectedTab("server.ts".equals(file));
        if (packageFileTab != null) packageFileTab.setSelectedTab("package.json".equals(file));
        refreshDependencyList();
    }

    private void captureEditor() {
        if (codeEditor == null) return;
        if ("package.json".equals(activeFile)) packageJsonSource = codeEditor.getText().toString();
        else serverSource = codeEditor.getText().toString();
    }

    private void removeSelectedDependency() {
        if (!editable() || selectedDependencyName == null) {
            setStatus("请先选中一个软件包。");
            return;
        }
        captureEditor();
        try {
            JSONObject root = new JSONObject(packageJsonSource);
            JSONObject dependencies = root.optJSONObject("dependencies");
            if (dependencies != null) dependencies.remove(selectedDependencyName);
            root.put("dependencies", dependencies == null ? new JSONObject() : dependencies);
            String removed = selectedDependencyName;
            selectedDependencyName = null;
            packageJsonSource = root.toString(2) + "\n";
            displayFile("package.json");
            onLog("已删除依赖 " + removed + "，保存后生效。");
            updateControls();
        } catch (Exception error) {
            setStatus("删除依赖失败：" + error.getMessage());
        }
    }

    private void openDependencyDialog() {
        if (!editable()) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), 0, dp(8), 0);
        EditText query = new EditText(this);
        query.setHint("搜索 npm 软件包");
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(query, new LinearLayout.LayoutParams(-1, dp(46)));
        TextView hint = label("输入至少 2 个字符。", 12, MUTED);
        root.addView(hint, match());
        ScrollView resultScroll = new ScrollView(this);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        resultScroll.addView(results, new ScrollView.LayoutParams(-1, -2));
        root.addView(resultScroll, new LinearLayout.LayoutParams(-1, dp(280)));
        final PackageCandidate[] selected = new PackageCandidate[1];
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("添加依赖")
                .setView(root).setNegativeButton("取消", null)
                .setPositiveButton("添加并安装", null).create();
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                int generation = ++searchGeneration;
                searchHandler.removeCallbacksAndMessages(null);
                searchHandler.postDelayed(() -> searchPackages(s.toString().trim(), generation,
                        results, hint, selected), 300L);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (selected[0] == null) {
                        hint.setText("请先选择一个软件包。");
                        return;
                    }
                    dialog.dismiss();
                    addDependency(selected[0]);
                }));
        dialog.show();
        query.requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void searchPackages(String query, int generation, LinearLayout results,
                                TextView hint, PackageCandidate[] selected) {
        if (generation != searchGeneration) return;
        if (query.length() < 2) {
            results.removeAllViews();
            hint.setText("输入至少 2 个字符。");
            selected[0] = null;
            return;
        }
        hint.setText("正在搜索…");
        ioExecutor.execute(() -> {
            List<PackageCandidate> found = new ArrayList<>();
            Throwable failure = null;
            HttpURLConnection connection = null;
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                connection = (HttpURLConnection) new URL(
                        "https://registry.npmjs.org/-/v1/search?text=" + encoded + "&size=12")
                        .openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                int code = connection.getResponseCode();
                String body = readInput(connection, code >= 200 && code < 300);
                if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
                JSONArray objects = new JSONObject(body).optJSONArray("objects");
                for (int i = 0; objects != null && i < objects.length(); i++) {
                    JSONObject pkg = objects.optJSONObject(i);
                    pkg = pkg == null ? null : pkg.optJSONObject("package");
                    if (pkg == null) continue;
                    String name = pkg.optString("name", "").trim();
                    if (name.length() == 0) continue;
                    found.add(new PackageCandidate(name, pkg.optString("version", "latest"),
                            pkg.optString("description", "暂无说明")));
                }
            } catch (Throwable error) {
                failure = error;
            } finally {
                if (connection != null) connection.disconnect();
            }
            Throwable resultFailure = failure;
            runOnUiThread(() -> {
                if (generation != searchGeneration) return;
                if (resultFailure != null) {
                    hint.setText("搜索失败：" + resultFailure.getMessage());
                    results.removeAllViews();
                } else renderCandidates(found, results, hint, selected);
            });
        });
    }

    private void renderCandidates(List<PackageCandidate> candidates, LinearLayout results,
                                  TextView hint, PackageCandidate[] selected) {
        results.removeAllViews();
        selected[0] = null;
        hint.setText(candidates.isEmpty() ? "没有找到匹配的软件包。" : "点击选择软件包。");
        for (PackageCandidate candidate : candidates) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(round(Color.WHITE, dp(6)));
            TextView title = label(candidate.name + "  " + candidate.version, 14, TEXT);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            TextView description = label(candidate.description, 12, MUTED);
            description.setMaxLines(2);
            row.addView(title, match());
            row.addView(description, match());
            row.setOnClickListener(view -> {
                selected[0] = candidate;
                for (int i = 0; i < results.getChildCount(); i++) {
                    results.getChildAt(i).setBackground(round(
                            results.getChildAt(i) == row ? Color.rgb(224, 241, 255) : Color.WHITE,
                            dp(6)));
                }
                hint.setText("已选择 " + candidate.name + "@" + candidate.version);
            });
            LinearLayout.LayoutParams params = match();
            params.bottomMargin = dp(5);
            results.addView(row, params);
        }
    }

    private void addDependency(PackageCandidate candidate) {
        captureEditor();
        try {
            JSONObject root = new JSONObject(packageJsonSource);
            JSONObject dependencies = root.optJSONObject("dependencies");
            if (dependencies == null) dependencies = new JSONObject();
            dependencies.put(candidate.name, candidate.version);
            root.put("dependencies", dependencies);
            packageJsonSource = root.toString(2) + "\n";
            selectedDependencyName = candidate.name;
            displayFile("package.json");
            showProjectSubpage("dependencies");
            onLog("已添加依赖 " + candidate.name + "@" + candidate.version + "。");
            saveProject(false, true);
        } catch (Exception error) {
            setStatus("添加依赖失败：" + error.getMessage());
        }
    }

    private static String readInput(HttpURLConnection connection, boolean success) throws IOException {
        InputStream input = success ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) throw new IOException("空响应");
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void refreshDependencyList() {
        if (dependencyList == null) return;
        dependencyList.removeAllViews();
        try {
            JSONObject root = new JSONObject(packageJsonSource);
            JSONObject dependencies = root.optJSONObject("dependencies");
            if (dependencies == null || dependencies.length() == 0) {
                dependencyList.addView(label("暂无依赖", 13, MUTED), match());
                return;
            }
            JSONArray names = dependencies.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String name = names.optString(i);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setClickable(true);
                row.setFocusable(true);
                row.setBackground(round(name.equals(selectedDependencyName)
                        ? Color.rgb(224, 241, 255) : Color.WHITE, dp(6)));
                row.setOnClickListener(view -> {
                    selectedDependencyName = name;
                    refreshDependencyList();
                    updateControls();
                });
                TextView packageName = label(name, 13, TEXT);
                packageName.setTypeface(Typeface.MONOSPACE);
                packageName.setSingleLine(true);
                packageName.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(packageName, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView version = label(dependencies.optString(name), 13, MUTED);
                version.setTypeface(Typeface.MONOSPACE);
                row.addView(version, new LinearLayout.LayoutParams(dp(125),
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                dependencyList.addView(row, match());
            }
        } catch (Exception error) {
            dependencyList.addView(label("package.json 不是有效 JSON", 13,
                    Color.rgb(151, 35, 48)), match());
        }
    }

    @Override
    public void onLog(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> onLog(message));
            return;
        }
        if (output == null) return;
        output.append(message + "\n\n");
        if (outputScroll != null) outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onReady(String baseUrl) {
        serverRunning = true;
        busy = false;
        endpoint.setText(baseUrl + "  ·  同一 Wi-Fi 可访问");
        setStatus("后端已启动");
        updateControls();
        backend.request("/api/health");
        backend.request("/api/format?text=Hello%20Android%20TV%20from%20npm");
    }

    @Override
    public void onStopped() {
        serverRunning = false;
        busy = false;
        endpoint.setText("服务未启动");
        setStatus("服务已停止，可再次启动。");
        updateControls();
    }

    @Override
    public void onBackendError(String message) {
        serverRunning = false;
        busy = false;
        setStatus("后端中断：" + message);
        updateControls();
    }

    @Override
    public void onInstallFinished(boolean success) {
        busy = false;
        setStatus(success ? "依赖安装完成。" : "依赖安装失败，请查看日志。");
        updateControls();
    }

    private void updateRuntimeText() {
        if (serverRunning) {
            endpoint.setText(backend.accessUrl() + "  ·  同一 Wi-Fi 可访问");
        } else {
            endpoint.setText("服务未启动");
        }
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text);
    }

    private void showPage(String page) {
        boolean runtime = "runtime".equals(page);
        runtimePage.setVisibility(runtime ? View.VISIBLE : View.GONE);
        projectPage.setVisibility(runtime ? View.GONE : View.VISIBLE);
        runtimeTab.setSelectedTab(runtime);
        projectTab.setSelectedTab(!runtime);
        if (runtime && outputScroll != null) outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showProjectSubpage(String page) {
        projectMainPage.setVisibility("main".equals(page) ? View.VISIBLE : View.GONE);
        directoryPage.setVisibility("directory".equals(page) ? View.VISIBLE : View.GONE);
        dependencyPage.setVisibility("dependencies".equals(page) ? View.VISIBLE : View.GONE);
        if ("dependencies".equals(page)) {
            captureEditor();
            refreshDependencyList();
        }
    }

    private boolean editable() {
        return workspaceReady && !busy && !serverRunning && !backend.isStartingOrReady();
    }

    private void updateControls() {
        boolean edit = editable();
        if (start != null) start.setEnabled(edit);
        if (stop != null) stop.setEnabled(serverRunning || backend.isStartingOrReady()
                || BackendService.isKeepAliveRequested(this));
        if (health != null) health.setEnabled(serverRunning);
        if (format != null) format.setEnabled(serverRunning);
        if (clearLogs != null) clearLogs.setEnabled(true);
        if (codeEditor != null) codeEditor.setEnabled(edit);
        if (save != null) save.setEnabled(edit);
        if (reset != null) reset.setEnabled(edit);
        if (addDependency != null) addDependency.setEnabled(edit);
        if (removeDependency != null) removeDependency.setEnabled(edit && selectedDependencyName != null);
        if (requestAllFiles != null) {
            boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    || Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
            requestAllFiles.setText(hasAllFilesAccess() ? "已启用公共路径权限" : "申请公共路径权限");
            requestAllFiles.setEnabled(supported && !hasAllFilesAccess());
        }
        if (serverFileTab != null) serverFileTab.setEnabled(workspaceReady);
        if (packageFileTab != null) packageFileTab.setEnabled(workspaceReady);
    }

    private FocusButton tab(String text) {
        FocusButton button = action(text, FocusButton.STYLE_TAB);
        return button;
    }

    private FocusButton action(String text, int style) {
        FocusButton button = new FocusButton(this);
        button.setText(text);
        button.setStyle(style);
        return button;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout pageContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), 0, dp(18));
        return content;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private LinearLayout.LayoutParams weightWithMargin(int margin) {
        LinearLayout.LayoutParams params = weight();
        params.rightMargin = margin;
        return params;
    }

    private LinearLayout.LayoutParams alignEnd() {
        LinearLayout.LayoutParams params = wrap();
        params.gravity = Gravity.RIGHT;
        return params;
    }

    private FrameLayout.LayoutParams frame() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class PackageCandidate {
        final String name;
        final String version;
        final String description;

        PackageCandidate(String name, String version, String description) {
            this.name = name;
            this.version = version;
            this.description = description == null || description.length() == 0
                    ? "暂无说明" : description;
        }
    }
}
