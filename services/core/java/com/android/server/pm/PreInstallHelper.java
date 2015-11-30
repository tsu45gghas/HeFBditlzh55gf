
package com.android.server.pm;

import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.Package;
import android.content.pm.PackageParser.PackageParserException;
import android.os.FileUtils;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class PreInstallHelper {
    final String TAG = "PreInstallHelper";
    static final boolean DEBUG = false;

    static final String PRE_INSTALLED_APK_FILE_DIR_PATH = "/data/system/helium/";
    static final String PRE_INSTALLED_APK_FILE_PATH = PRE_INSTALLED_APK_FILE_DIR_PATH + "pre_installed_packages.list";

    static final String PRINSTALLED_APK_PATH = "/vendor/helium/app";
    private PackageManagerService mPms;
    private HashSet<String> mPackageNameCahce;
    private ArrayList<String> mPreloadPackagesList;
    private HashSet<String> mNewCopiedPackages;

    private boolean mNewApksCopied = false;

    public PreInstallHelper(PackageManagerService service) {
        mPms = service;
    }

    void onPmsStartup() {
        if (mPms != null) {
            if (DEBUG) {
                Slog.i(TAG, "onPmsStartup... ");
            }

            if (mPms.isFirstBoot()) {
                preloadThirdPartyApks();
            } else if (mPms.isUpgrade()) {
                checkForSystemUpgrade();
            }
        }
    }

    void beforeScanAppInstallDir() {
        if (mPms.isFirstBoot()) {
            mPackageNameCahce = new HashSet<String>(mPms.mPackages.keySet());
        }
    }

    void afterScanAppInstallDir() {
        if (mPms.isFirstBoot()) {
            if (DEBUG) {
                Slog.i(TAG, "afterScanAppInstallDir()");
            }

            ArrayList<String> preinstalledPkgs = findOutNewInstalledApks();
            if (preinstalledPkgs.size() > 0) {
                mPreloadPackagesList = preinstalledPkgs;
                storePreinstalledPackagesList();
            } else {
                Slog.w(TAG, "No Pre-Installed package???");
            }
        }
    }

    private ArrayList<String> findOutNewInstalledApks() {
        ArrayList<String> preinstalledPkgs = new ArrayList<String>();
        if (mPackageNameCahce != null) {
            Set<String> pkgCache = mPackageNameCahce;
            ArrayMap<String, Package> packageMap = mPms.mPackages;
            final int count = packageMap.size();
            for (int i = 0; i < count; i++) {
                String pkgName = packageMap.keyAt(i);
                if (!pkgCache.contains(pkgName)) {
                    if (DEBUG) {
                        Slog.i(TAG, "New installed package: " + pkgName);
                    }
                    preinstalledPkgs.add(pkgName);
                }
            }
        }

        return preinstalledPkgs;
    }

    /**
     * 释放预装的第三方的apk到"/data/app"目录
     */
    private void preloadThirdPartyApks() {
        if (DEBUG) {
            Slog.i(TAG, "preloadThirdPartyApks()");
        }

        File dir = new File(PRINSTALLED_APK_PATH);
        if (dir.exists() && dir.isDirectory()) {
            File[] subDirs = dir.listFiles();
            if (subDirs != null) {
                for (File subdir : subDirs) {
                    copyApkToAppInstallDir(subdir);
                }
            }
        }
    }

    private void checkForSystemUpgrade() {
        if (DEBUG) {
            Slog.i(TAG, "preloadThirdPartyApks()");
        }

        File dir = new File(PRINSTALLED_APK_PATH);
        if (dir.exists() && dir.isDirectory()) {
            File[] subDirs = dir.listFiles();
            if (subDirs != null) {
                loadPreinstalledPackagesList();
                mPackageNameCahce = new HashSet<String>(mPms.mPackages.keySet());
                mNewCopiedPackages = new HashSet<String>();
                mNewApksCopied = false;
                for (File subdir : subDirs) {
                    File[] apkFiles = subdir.listFiles(new FilenameFilter() {
                        @Override
                        public boolean accept(File dir, String filename) {
                            return filename != null && filename.endsWith(".apk");
                        }
                    });

                    if (apkFiles != null && apkFiles.length == 1) {
                        checkPackageNeedReinstall(apkFiles[0]);
                    }
                }

                if (mNewApksCopied) {
                    if (DEBUG) {
                        Slog.i(TAG, "New Apk file(s) has been copied to /data/app/...");
                    }
                    ArrayList<String> newInstalledPackages = findOutNewInstalledApks();
                    if (newInstalledPackages != null) {
                        for (String pkgName : newInstalledPackages) {
                            if (mNewCopiedPackages.contains(pkgName)) {
                                mPreloadPackagesList.add(pkgName);
                            }
                        }
                        storePreinstalledPackagesList();
                    }
                }
            }
        }
    }

    private void storePreinstalledPackagesList() {
        if (DEBUG) {
            Slog.i(TAG, "storePreinstalledPackagesList()");
        }

        ArrayList<String> preinstalledPkgs = mPreloadPackagesList;
        if (preinstalledPkgs != null) {
            File dir = new File(PRE_INSTALLED_APK_FILE_DIR_PATH);
            if (!dir.exists() && !dir.mkdir()) {
                Slog.e(TAG, "Error creating dir:" + PRE_INSTALLED_APK_FILE_DIR_PATH);
            }

            StringBuilder sb = new StringBuilder();
            for (String pkgName : preinstalledPkgs) {
                if (DEBUG) {
                    Slog.i(TAG, "Pre-installed package: ()" + pkgName);
                }

                sb.append(pkgName).append('\n');
            }

            FileWriter fw = null;
            try {
                fw = new FileWriter(PRE_INSTALLED_APK_FILE_PATH, false);
                fw.write(sb.toString());
                fw.flush();
            } catch (IOException e) {
                Slog.w(TAG, "Error writing pre-installed packages file:", e);
            } finally {
                if (fw != null) {
                    try {
                        fw.close();
                    } catch (IOException e) {
                        Slog.w(TAG, "Error closing pre-installed packages file:", e);
                    }
                }
            }
        }
    }

    private void loadPreinstalledPackagesList() {
        if (DEBUG) {
            Slog.i(TAG, "loadPreinstalledPackagesList()");
        }

        ArrayList<String> preInstalledpackagesList = new ArrayList<String>();
        File file = new File(PRE_INSTALLED_APK_FILE_PATH);
        BufferedReader br = null;
        if (file.exists()) {
            try {
                br = new BufferedReader(new FileReader(file));
                String line = null;
                while ((line = br.readLine()) != null) {
                    if (DEBUG) {
                        Slog.i(TAG, "Pre-Installed package:" + line);
                    }
                    preInstalledpackagesList.add(line);
                }
            } catch (Exception e) {
                Slog.w(TAG, "Error reading pre-installed packages file:", e);
            } finally {
                if (br != null) {
                    try {
                        br.close();
                    } catch (Exception e) {
                        Slog.w(TAG, "Error closing pre-installed packages file:", e);
                    }
                }
            }

            mPreloadPackagesList = preInstalledpackagesList;
        }
    }

    private void checkPackageNeedReinstall(File apkFile) {
        PackageManagerService pms = mPms;
        PackageParser pp = new PackageParser();
        pp.setSeparateProcesses(pms.mSeparateProcesses);
        pp.setOnlyCoreApps(pms.mOnlyCore);
        pp.setDisplayMetrics(pms.mMetrics);

        final PackageParser.Package pkg;
        try {
            pkg = pp.parsePackage(apkFile, 0);
        } catch (PackageParserException e) {
            Slog.w(TAG, "Error: Parsing apk failed: " + apkFile.getName());
            return;
        }

        PackageSetting ps = null;
        synchronized (pms.mPackages) {
            ps = pms.mSettings.peekPackageLPr(pkg.packageName);
        }

        final String pkgName = pkg.packageName;
        if (ps != null) {
            if (ps.versionCode >= pkg.mVersionCode) {
                if (DEBUG) {
                    Slog.i(TAG, "Same/newer version has been installed: " + pkg.packageName);
                }
            } else {
                if (DEBUG) {
                    Slog.i(TAG, "Older version found: " + pkgName);
                }

                try {
                    pp.collectCertificates(pkg, 0);
                } catch (PackageParserException e) {
                    Slog.w(TAG, "Fetching signature failed: " + pkgName);
                    return;
                }

                if (PackageManagerService.compareSignatures(ps.signatures.mSignatures, pkg.mSignatures) != PackageManager.SIGNATURE_MATCH) {
                    Slog.w(TAG, "Inconsistent signature: " + pkgName);
                    return;
                }

                synchronized (pms.mInstallLock) {
                    int deleted = pms.deletePackageX(pkgName, UserHandle.USER_ALL, PackageManager.DELETE_KEEP_DATA);
                    if (DEBUG) {
                        Slog.i(TAG, "Delete package ret:" + deleted);
                    }
                }
                copyApkToAppInstallDir(apkFile.getParentFile());
                mNewCopiedPackages.add(pkgName);
            }
        } else {
            if (mPreloadPackagesList.contains(pkgName)) {
                if (DEBUG) {
                    Slog.i(TAG, "Package has been previously installed, but now un-installed: " + pkgName);
                }
            } else {
                if (DEBUG) {
                    Slog.i(TAG, "Package has NOT been installed: " + pkgName);
                }
                copyApkToAppInstallDir(apkFile.getParentFile());
                mNewCopiedPackages.add(pkgName);
            }
        }
    }

    private void copyApkToAppInstallDir(File apkDir) {
        if (apkDir != null && apkDir.exists() && apkDir.isDirectory()) {
            File targetDir = new File(mPms.mAppInstallDir, apkDir.getName());
            if (targetDir.exists()) {
                Slog.w(TAG, "Error: vendor apk dir already exists: " + targetDir.getName());
                return;
            }

            targetDir.mkdir();
            if (FileUtils.setPermissions(targetDir.getAbsolutePath(), 0771, -1, -1) != 0) {
                Slog.e(TAG, "Cannot chmod dir: " + targetDir.getAbsolutePath());
                return;
            }

            File[] apkFiles = apkDir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String filename) {
                    return filename != null && filename.endsWith(".apk");
                }
            });

            if (apkFiles != null && apkFiles.length == 1) {
                File targetFile = new File(targetDir, apkFiles[0].getName());
                if (FileUtils.copyFile(apkFiles[0], targetFile)) {
                    if (FileUtils.setPermissions(targetFile.getAbsolutePath(), 0644, -1, -1) != 0) {
                        Slog.e(TAG, "Cannot chmod file: " + targetFile.getAbsolutePath());
                        return;
                    }

                    mNewApksCopied = true;
                    Slog.e(TAG, "Copy file succeeded: " + targetFile.getAbsolutePath());
                }
            }
        }
    }
}
