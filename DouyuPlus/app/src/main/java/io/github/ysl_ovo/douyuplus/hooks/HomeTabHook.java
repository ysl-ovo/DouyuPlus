package io.github.ysl_ovo.douyuplus.hooks;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.ysl_ovo.douyuplus.DouyuLog;
import io.github.ysl_ovo.douyuplus.DouyuPlusConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

/**
 * 首页底部栏“关注”页面修复
 * 1. 把第 2 个“娱乐”改为“关注”
 * 2. 默认展示【直播中】分栏 (index 1)
 * 3. 搜索栏下沉适配状态栏，消除黑条
 */
public class HomeTabHook {
    private static final String TAG = "DouyuPlus_HomeTab";
    private static volatile boolean sRestoringFollowSort = false;

    public static void hook(final XC_LoadPackage.LoadPackageParam lpparam) {
        final boolean enableFollowTab = DouyuPlusConfig.isHomeFollowTabEnabled();
        int hookedTargets = 0;

        // 1. Hook BottomTabDataSourceUtil: 修改数据源配置中的 Tab 名称为“关注”
        if (enableFollowTab) {
            try {
                Class<?> tabDataUtilCls = XposedHelpers.findClass(
                        "com.douyu.module.home.bottomtab.BottomTabDataSourceUtil",
                        lpparam.classLoader
                );

                XC_MethodHook configHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object config = param.getResult();
                        if (config != null) {
                            try {
                                List<?> confList = (List<?>) XposedHelpers.getObjectField(config, "bottomConf");
                                if (confList != null) {
                                    for (Object item : confList) {
                                        String type = (String) XposedHelpers.getObjectField(item, "type");
                                        if ("fun".equals(type)) {
                                            Object oldName = XposedHelpers.getObjectField(item, "name");
                                            if (!"关注".equals(oldName)) {
                                                XposedHelpers.setObjectField(item, "name", "关注");
                                                DouyuLog.d(TAG, "BottomTabDataSourceUtil: Changed tab 'fun' name to '关注'");
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "Failed to modify BottomTabConfig", t);
                            }
                        }
                    }
                };

                boolean hooked = false;
                for (Method m : tabDataUtilCls.getDeclaredMethods()) {
                    if (m.getName().equals("a") || m.getName().equals("d")) {
                        XposedBridge.hookMethod(m, configHook);
                        hooked = true;
                    }
                }
                if (hooked) hookedTargets++;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to hook BottomTabDataSourceUtil", t);
            }
        }

        // 2. Hook BottomTabFactory: 将第二个 Tab 替换为关注页面，以及可选去除底栏「+」号
        try {
            Class<?> tabFactoryCls = XposedHelpers.findClass(
                    "com.douyu.module.home.bottomtab.BottomTabFactory",
                    lpparam.classLoader
            );

            for (Method m : tabFactoryCls.getDeclaredMethods()) {
                if (enableFollowTab && m.getName().equals("a") && m.getParameterTypes().length == 2) {
                    // Hook a: 替换 Tab 2 为关注 Fragment
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object bean = param.getResult();
                            if (bean == null) return;

                            int index = XposedHelpers.getIntField(bean, "mIndex");
                            String type = (String) XposedHelpers.getObjectField(bean, "type");

                            if (index == 1 || "fun".equals(type)) {
                                XposedHelpers.setObjectField(bean, "mTitle", "关注");
                                Object followFragment = createFollowFragment(lpparam.classLoader);
                                if (followFragment != null) {
                                    XposedHelpers.setObjectField(bean, "mFragment", followFragment);
                                    DouyuLog.d(TAG, "Replaced Tab 2 (Entertainment) with MyFollowTabFragment");
                                }
                            }
                        }
                    });
                    hookedTargets++;
                } else if (m.getName().equals("b") && m.getParameterTypes().length == 0) {
                    // Hook b: 当开启去除底栏+号时，从返回的 BottomTabBean 列表中移除 send 项
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (DouyuPlusConfig.isHideBottomPlus()) {
                                List<?> list = (List<?>) param.getResult();
                                if (list != null) {
                                    Iterator<?> it = list.iterator();
                                    while (it.hasNext()) {
                                        Object bean = it.next();
                                        String type = (String) XposedHelpers.getObjectField(bean, "type");
                                        if ("send".equals(type)) {
                                            it.remove();
                                            DouyuLog.d(TAG, "BottomTabFactory.b: Removed '+' button from BottomTabBeans");
                                        }
                                    }
                                }
                            }
                        }
                    });
                    hookedTargets++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook BottomTabFactory", t);
        }

        // 2.1 Hook BottomTabBar.d: 当开启去除底栏+号时，保证传入的第二个参数 (BottomTabBean) 为 null
        try {
            Class<?> bottomTabBarCls = XposedHelpers.findClass(
                    "com.douyu.module.home.widget.BottomTabBar",
                    lpparam.classLoader
            );

            for (Method m : bottomTabBarCls.getDeclaredMethods()) {
                if (m.getName().equals("d") && m.getParameterTypes().length == 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (DouyuPlusConfig.isHideBottomPlus()) {
                                param.args[1] = null;
                            }
                        }
                    });
                    hookedTargets++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook BottomTabBar.d", t);
        }

        // 2.2 Hook MainFragment: 支持自定义冷启动打开 App 后的默认页面 (四选一)
        try {
            Class<?> mainFragmentCls = XposedHelpers.findClass(
                    "com.douyu.module.home.pages.main.MainFragment",
                    lpparam.classLoader
            );

            for (Method m : mainFragmentCls.getDeclaredMethods()) {
                if (m.getName().equals("OB") && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == int.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            int defaultPage = DouyuPlusConfig.getDefaultStartPage();
                            if (defaultPage > 0) {
                                int curArg = (Integer) param.args[0];
                                if (curArg == 0) {
                                    param.args[0] = defaultPage;
                                    DouyuLog.d(TAG, "MainFragment.OB: Overriding start page from 0 to " + defaultPage);
                                }
                            }
                        }
                    });
                    hookedTargets++;
                }
            }

            XposedHelpers.findAndHookMethod(
                    mainFragmentCls,
                    "onViewCreated",
                    View.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Bundle savedInstanceState = (Bundle) param.args[1];
                            if (savedInstanceState == null) {
                                int defaultPage = DouyuPlusConfig.getDefaultStartPage();
                                if (defaultPage > 0) {
                                    try {
                                        int curG = XposedHelpers.getIntField(param.thisObject, "g");
                                        if (curG == 0) {
                                            XposedHelpers.setIntField(param.thisObject, "g", defaultPage);
                                            DouyuLog.d(TAG, "MainFragment.onViewCreated: Overriding g to " + defaultPage);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
            );
            hookedTargets++;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook MainFragment start page", t);
        }

        // 2b. 关注页自动记忆上次排序方式（不依赖底栏替换开关）
        if (hookFollowSortPersistence(lpparam.classLoader)) {
            hookedTargets++;
        }

        if (!enableFollowTab) {
            Log.i(TAG, "Hooked HomeTab (" + hookedTargets + " targets)");
            return;
        }

        // 4. Hook MyFollowTabFragment: 仅针对底栏替换的实例，默认定位到「直播中」分栏 (index = 1)，并沉浸下沉搜索栏
        try {
            final Class<?> myFollowTabCls = XposedHelpers.findClass(
                    "com.douyu.module.follow.p.live.page.myfollow.MyFollowTabFragment",
                    lpparam.classLoader
            );

            // 仅在底栏关注页中强制 defaultSubTab = 1 (TAB_SUB_LIVE 直播中)，不影响原关注页 FollowFansActivity
            try {
                XposedHelpers.findAndHookMethod(myFollowTabCls, "hC", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isBottomTabFollowFragment(param.thisObject, null)) {
                            param.setResult(1);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            try {
                for (Method m : myFollowTabCls.getDeclaredMethods()) {
                    if (m.getName().equals("eC") && m.getReturnType() == int.class) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (isBottomTabFollowFragment(param.thisObject, null)) {
                                    param.setResult(1);
                                }
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {}

            // Hook onViewCreated
            XposedHelpers.findAndHookMethod(
                    myFollowTabCls,
                    "onViewCreated",
                    View.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            final Object fragment = param.thisObject;
                            final View root = (View) param.args[0];
                            if (!isBottomTabFollowFragment(fragment, root)) {
                                return;
                            }
                            if (root != null) {
                                Context ctx = root.getContext();
                                boolean isDark = isNightMode(ctx, lpparam.classLoader);
                                // 根据深色/浅色模式动态设置背景色，解决深色模式下状态栏白条与下拉露白问题
                                root.setBackgroundColor(isDark ? 0xFF131313 : 0xFFFFFFFF);

                                int statusBarHeight = 0;
                                int resId = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
                                if (resId > 0) {
                                    statusBarHeight = ctx.getResources().getDimensionPixelSize(resId);
                                }
                                if (statusBarHeight <= 0) {
                                    statusBarHeight = (int) (ctx.getResources().getDisplayMetrics().density * 36);
                                }

                                final int finalStatusBarHeight = statusBarHeight;

                                // 精确给 AppBarLayout 或 searchBar 增加 topMargin 避开系统状态栏
                                View searchBar = root.findViewById(2131377643); // 0x7f0a3e6b
                                if (searchBar != null) {
                                    View parent = (View) searchBar.getParent();
                                    if (parent != null && parent.getClass().getName().contains("AppBarLayout")) {
                                        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) parent.getLayoutParams();
                                        if (mlp != null) {
                                            mlp.topMargin = finalStatusBarHeight;
                                            parent.setLayoutParams(mlp);
                                        }
                                    } else {
                                        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) searchBar.getLayoutParams();
                                        if (mlp != null) {
                                            mlp.topMargin = finalStatusBarHeight;
                                            searchBar.setLayoutParams(mlp);
                                        }
                                    }
                                }

                                // 延时稳定选中「直播中」(index 1)
                                root.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        switchToLiveSubTab(myFollowTabCls, fragment);
                                    }
                                }, 50L);
                            }
                        }
                    }
            );

            // Hook onResume 和 onHiddenChanged，用户每次切到底部关注 Tab 时，若在 index 0 自动切换到 index 1
            try {
                XposedHelpers.findAndHookMethod(myFollowTabCls, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (isBottomTabFollowFragment(param.thisObject, null)) {
                            switchToLiveSubTab(myFollowTabCls, param.thisObject);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(myFollowTabCls, "onHiddenChanged", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        boolean hidden = (Boolean) param.args[0];
                        if (!hidden && isBottomTabFollowFragment(param.thisObject, null)) {
                            switchToLiveSubTab(myFollowTabCls, param.thisObject);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            hookedTargets++;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook MyFollowTabFragment", t);
        }

        // 5. Hook AllFollowFragment: 消除筛选栏/分组横条纯黑背景
        try {
            Class<?> allFollowCls = XposedHelpers.findClass(
                    "com.douyu.module.follow.p.live.page.myfollow.tab.AllFollowFragment",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(
                    allFollowCls,
                    "onViewCreated",
                    View.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            View view = (View) param.args[0];
                            if (!isBottomTabFollowFragment(param.thisObject, view)) {
                                return;
                            }
                            if (view != null) {
                                boolean isDark = isNightMode(view.getContext(), lpparam.classLoader);
                                view.setBackgroundColor(isDark ? 0xFF131313 : 0xFFFFFFFF);

                                // 找到 RadioGroup (0x7f0a39d9 或 2131376601) 并设为深色/纯白背景
                                View radioGroup = view.findViewById(0x7f0a39d9);
                                if (radioGroup == null) {
                                    radioGroup = view.findViewById(2131376601);
                                }
                                if (radioGroup != null) {
                                    int tabBg = isDark ? 0xFF171717 : 0xFFFFFFFF;
                                    radioGroup.setBackgroundColor(tabBg);
                                    if (radioGroup.getParent() instanceof View) {
                                        ((View) radioGroup.getParent()).setBackgroundColor(tabBg);
                                    }
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // 6. Hook HomeProviderUtil.A: 底栏重复点击时触发“关注”页面回到顶部并刷新
        try {
            Class<?> homeProviderUtilCls = XposedHelpers.findClass(
                    "com.douyu.module.home.utils.HomeProviderUtil",
                    lpparam.classLoader
            );
            boolean hooked = false;
            for (Method m : homeProviderUtilCls.getDeclaredMethods()) {
                if (m.getName().equals("A") && m.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object fragment = param.args[0];
                            if (fragment != null) {
                                String name = fragment.getClass().getName();
                                if (name.contains("MyFollowTabFragment") || name.contains("FollowFragment")) {
                                    DouyuLog.d(TAG, "HomeProviderUtil.A intercepted for follow tab fragment: " + name);
                                    refreshFollowTab(fragment);
                                }
                            }
                        }
                    });
                    hooked = true;
                }
            }
            if (hooked) hookedTargets++;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook HomeProviderUtil.A", t);
        }

        Log.i(TAG, "Hooked HomeTab (" + hookedTargets + " targets)");
    }

    private static void switchToLiveSubTab(Class<?> myFollowTabCls, Object fragment) {
        try {
            for (Field f : myFollowTabCls.getDeclaredFields()) {
                if (f.getType().getName().contains("SlidingTabLayout")) {
                    f.setAccessible(true);
                    Object tl = f.get(fragment);
                    if (tl != null) {
                        XposedHelpers.callMethod(tl, "setCurrentTab", 1);
                    }
                } else if (f.getType().getName().contains("ViewPager")) {
                    f.setAccessible(true);
                    Object vp = f.get(fragment);
                    if (vp != null) {
                        XposedHelpers.callMethod(vp, "setCurrentItem", 1, false);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static android.app.Activity resolveFragmentActivity(Object fragment, View root) {
        if (fragment != null) {
            try {
                Object act = XposedHelpers.callMethod(fragment, "getActivity");
                if (act instanceof android.app.Activity) {
                    return (android.app.Activity) act;
                }
            } catch (Throwable ignored) {}
        }
        if (root != null) {
            Context ctx = root.getContext();
            while (ctx instanceof android.content.ContextWrapper) {
                if (ctx instanceof android.app.Activity) {
                    return (android.app.Activity) ctx;
                }
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            }
        }
        return null;
    }

    private static boolean hasBottomTabMarker(Object fragment) {
        if (fragment == null) return false;
        try {
            Object argsObj = XposedHelpers.callMethod(fragment, "getArguments");
            if (argsObj instanceof Bundle && ((Bundle) argsObj).getBoolean("douyuplus_bottom_follow_tab", false)) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isBottomTabFollowFragment(Object fragment, View root) {
        android.app.Activity activity = resolveFragmentActivity(fragment, root);
        if (activity != null) {
            String actName = activity.getClass().getName();
            if (actName.contains("FollowFansActivity")) {
                return false;
            }
            if (actName.contains("MainActivity")) {
                return true;
            }
        }
        if (hasBottomTabMarker(fragment)) {
            return true;
        }
        if (fragment != null) {
            try {
                Object parent = XposedHelpers.callMethod(fragment, "getParentFragment");
                if (parent != null && hasBottomTabMarker(parent)) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void setSortFieldSafe(Object vm, String obfuscatedField, int sortValue) {
        if (vm == null) return;
        try {
            java.lang.reflect.Field field = XposedHelpers.findFieldIfExists(vm.getClass(), obfuscatedField);
            if (field == null) {
                field = XposedHelpers.findFieldIfExists(vm.getClass(), "currentSort");
            }
            if (field != null) {
                field.setAccessible(true);
                Class<?> t = field.getType();
                if (t == int.class) {
                    field.setInt(vm, sortValue);
                } else if (t == Integer.class) {
                    field.set(vm, Integer.valueOf(sortValue));
                } else {
                    Log.w(TAG, "Unexpected sort field type on " + vm.getClass().getName() + ": " + t.getName());
                    return;
                }
                DouyuLog.d(TAG, "Restored follow sort " + sortValue + " on " + vm.getClass().getSimpleName() + "." + field.getName());
            } else {
                Log.w(TAG, "Sort field not found on " + vm.getClass().getName() + " (tried " + obfuscatedField + ", currentSort)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to restore sort field on " + vm.getClass().getName(), t);
        }
    }

    private static Object createFollowFragment(ClassLoader classLoader) {
        try {
            Class<?> myFollowTabCls = XposedHelpers.findClass(
                    "com.douyu.module.follow.p.live.page.myfollow.MyFollowTabFragment",
                    classLoader
            );
            Object fragment = myFollowTabCls.newInstance();
            Bundle bundle = new Bundle();
            bundle.putInt("default_sub_tab", 1);
            bundle.putBoolean("douyuplus_bottom_follow_tab", true);
            XposedHelpers.callMethod(fragment, "setArguments", bundle);
            DouyuLog.d(TAG, "Created MyFollowTabFragment with default_sub_tab = 1");
            return fragment;
        } catch (Throwable t1) {
            Log.w(TAG, "MyFollowTabFragment creation failed, trying fallback", t1);
        }

        try {
            Class<?> newFollowCls = XposedHelpers.findClass(
                    "com.douyu.module.follow.p.live.page.follow.online.NewFollowFragment",
                    classLoader
            );
            return newFollowCls.newInstance();
        } catch (Throwable t2) {
            Log.w(TAG, "NewFollowFragment creation failed", t2);
        }

        return null;
    }

    public static boolean isNightMode(Context ctx, ClassLoader cl) {
        try {
            Class<?> themeUtils = XposedHelpers.findClass("com.douyu.lib.theme.BaseThemeUtils", cl);
            return (Boolean) XposedHelpers.callStaticMethod(themeUtils, "i");
        } catch (Throwable ignored) {}
        try {
            if (ctx != null) {
                return (ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static long sLastFollowRefreshTime = 0L;

    public static void refreshFollowTab(final Object followTabFrag) {
        if (followTabFrag == null) return;
        long now = System.currentTimeMillis();
        if (now - sLastFollowRefreshTime < 800L) {
            DouyuLog.d(TAG, "refreshFollowTab: ignored due to debounce (" + (now - sLastFollowRefreshTime) + "ms)");
            return;
        }
        sLastFollowRefreshTime = now;

        try {
            // 1. 获取 ViewPager 及当前处于的子 Tab 索引
            Object viewPager = null;
            try {
                viewPager = XposedHelpers.getObjectField(followTabFrag, "viewPager");
            } catch (Throwable ignored) {}
            if (viewPager == null) {
                try {
                    viewPager = XposedHelpers.getObjectField(followTabFrag, "e");
                } catch (Throwable ignored) {}
            }

            int currentItem = -1;
            if (viewPager != null) {
                try {
                    currentItem = (Integer) XposedHelpers.callMethod(viewPager, "getCurrentItem");
                } catch (Throwable ignored) {}
            }

            // 2. 获取子 Fragment 列表
            List<?> subFrags = null;
            try {
                subFrags = (List<?>) XposedHelpers.callMethod(followTabFrag, "lC");
            } catch (Throwable ignored) {}

            Object targetSubFrag = null;
            if (subFrags != null && currentItem >= 0 && currentItem < subFrags.size()) {
                targetSubFrag = subFrags.get(currentItem);
            }

            final Object finalSubFrag = targetSubFrag;

            // 3. 在 UI 线程执行置顶与刷新
            View anchorView = null;
            if (targetSubFrag != null) {
                try {
                    anchorView = (View) XposedHelpers.callMethod(targetSubFrag, "getView");
                } catch (Throwable ignored) {}
            }
            if (anchorView == null) {
                try {
                    anchorView = (View) XposedHelpers.callMethod(followTabFrag, "getView");
                } catch (Throwable ignored) {}
            }

            Runnable action = new Runnable() {
                @Override
                public void run() {
                    try {
                        doTriggerRefresh(followTabFrag, finalSubFrag);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed in doTriggerRefresh", t);
                    }
                }
            };

            if (anchorView != null) {
                anchorView.post(action);
            } else {
                new Handler(Looper.getMainLooper()).post(action);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to refresh follow tab", t);
        }
    }

    private static void doTriggerRefresh(Object followTabFrag, Object targetSubFrag) {
        View followRootView = null;
        try {
            followRootView = (View) XposedHelpers.callMethod(followTabFrag, "getView");
        } catch (Throwable ignored) {}

        View subRootView = null;
        if (targetSubFrag != null) {
            try {
                subRootView = (View) XposedHelpers.callMethod(targetSubFrag, "getView");
            } catch (Throwable ignored) {}
        }

        // 1. 如果 AppBarLayout 折叠了，先展开 AppBarLayout（让顶部搜索栏露出）
        if (followRootView != null) {
            View appBar = findViewByClassName(followRootView, "AppBarLayout");
            if (appBar != null) {
                try {
                    XposedHelpers.callMethod(appBar, "setExpanded", true, true);
                } catch (Throwable ignored) {}
            }
        }

        // 2. 获取当前分栏的 RecyclerView 和 DYRefreshLayout
        View refreshLayout = null;
        View recyclerView = null;

        // 2.1 优先从 targetSubFrag 字段按类型安全扫描
        if (targetSubFrag != null) {
            try {
                Class<?> curCls = targetSubFrag.getClass();
                while (curCls != null && curCls != Object.class) {
                    for (Field f : curCls.getDeclaredFields()) {
                        if (refreshLayout == null && f.getType().getName().contains("RefreshLayout")) {
                            f.setAccessible(true);
                            View v = (View) f.get(targetSubFrag);
                            if (isRefreshLayout(v)) refreshLayout = v;
                        }
                        if (recyclerView == null && f.getType().getName().contains("RecyclerView")) {
                            f.setAccessible(true);
                            View v = (View) f.get(targetSubFrag);
                            if (isRecyclerView(v)) recyclerView = v;
                        }
                    }
                    curCls = curCls.getSuperclass();
                }
            } catch (Throwable ignored) {}
        }

        // 2.2 从 subRootView 按 ID 2131378308 及类名查找
        if (subRootView != null) {
            if (refreshLayout == null) {
                View v = subRootView.findViewById(2131378308);
                if (isRefreshLayout(v)) refreshLayout = v;
            }
            if (refreshLayout == null) {
                refreshLayout = findViewByClassName(subRootView, "RefreshLayout");
            }
            if (recyclerView == null) {
                recyclerView = findViewByClassName(subRootView, "RecyclerView");
            }
        }

        // 2.3 从 followRootView 兜底查找
        if (followRootView != null) {
            if (refreshLayout == null) {
                View v = followRootView.findViewById(2131378308);
                if (isRefreshLayout(v)) refreshLayout = v;
            }
            if (refreshLayout == null) {
                refreshLayout = findViewByClassName(followRootView, "RefreshLayout");
            }
            if (recyclerView == null) {
                recyclerView = findViewByClassName(followRootView, "RecyclerView");
            }
        }

        // 严格安全校验：确保 refreshLayout 确实是 RefreshLayout
        if (refreshLayout != null && !isRefreshLayout(refreshLayout)) {
            refreshLayout = null;
        }
        if (recyclerView != null && !isRecyclerView(recyclerView)) {
            recyclerView = null;
        }

        DouyuLog.d(TAG, "doTriggerRefresh: subFrag=" + (targetSubFrag != null ? targetSubFrag.getClass().getSimpleName() : "null")
                + ", refreshLayout=" + (refreshLayout != null ? refreshLayout.getClass().getSimpleName() : "null")
                + ", recyclerView=" + (recyclerView != null ? recyclerView.getClass().getSimpleName() : "null"));

        // 3. 如果 RecyclerView 已经被滑下来了，先将其快速滚回顶部
        if (recyclerView != null) {
            try {
                if (recyclerView.canScrollVertically(-1)) {
                    XposedHelpers.callMethod(recyclerView, "scrollToPosition", 0);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to scrollToPosition(0)", t);
            }
        }

        // 4. 在 refreshLayout 上触发 autoRefresh 下拉刷新
        if (refreshLayout != null) {
            final View finalRefreshLayout = refreshLayout;
            finalRefreshLayout.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean isRefreshing = false;
                        try {
                            Object state = XposedHelpers.callMethod(finalRefreshLayout, "getState");
                            if (state != null && state.toString().toLowerCase().contains("refreshing")) {
                                isRefreshing = true;
                            }
                        } catch (Throwable ignored) {}

                        if (!isRefreshing) {
                            XposedHelpers.callMethod(finalRefreshLayout, "autoRefresh");
                            DouyuLog.d(TAG, "autoRefresh invoked successfully on " + finalRefreshLayout.getClass().getSimpleName());
                        } else {
                            DouyuLog.d(TAG, "RefreshLayout is already refreshing, ignored");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to call autoRefresh", t);
                    }
                }
            });
        }
    }

    private static boolean isRefreshLayout(View v) {
        return v != null && v.getClass().getName().contains("RefreshLayout");
    }

    private static boolean isRecyclerView(View v) {
        return v != null && v.getClass().getName().contains("RecyclerView");
    }

    private static View findViewByClassName(View root, String className) {
        if (root == null) return null;
        if (root.getClass().getName().contains(className)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View res = findViewByClassName(vg.getChildAt(i), className);
                if (res != null) return res;
            }
        }
        return null;
    }

    /**
     * 关注页（MyFollowTabFragment）自动记忆并恢复「全部」「直播中」两个分栏的上次排序方式。
     */
    private static boolean hookFollowSortPersistence(ClassLoader classLoader) {
        try {
            final Class<?> tabVmCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.follow.p.live.page.myfollow.MyFollowTabViewModel",
                    classLoader
            );
            final Class<?> sortEnumCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.follow.p.live.page.myfollow.FollowSortType",
                    classLoader
            );
            if (tabVmCls != null && sortEnumCls != null) {
                XposedBridge.hookAllConstructors(tabVmCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int allSort = DouyuPlusConfig.getFollowAllSort(null);
                        int liveSort = DouyuPlusConfig.getFollowLiveSort(null);
                        sRestoringFollowSort = true;
                        try {
                            if (allSort != DouyuPlusConfig.DEFAULT_FOLLOW_SORT) {
                                Object allEnum = findFollowSortTypeByValue(sortEnumCls, allSort);
                                if (allEnum != null) {
                                    XposedHelpers.callMethod(param.thisObject, "L", 0, allEnum);
                                }
                            }
                            if (liveSort != DouyuPlusConfig.DEFAULT_FOLLOW_SORT) {
                                Object liveEnum = findFollowSortTypeByValue(sortEnumCls, liveSort);
                                if (liveEnum != null) {
                                    XposedHelpers.callMethod(param.thisObject, "L", 1, liveEnum);
                                }
                            }
                        } finally {
                            sRestoringFollowSort = false;
                        }
                    }
                });

                XposedHelpers.findAndHookMethod(tabVmCls, "L", int.class, sortEnumCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (sRestoringFollowSort || param.args == null || param.args.length < 2 || param.args[1] == null) {
                            return;
                        }
                        int position = (int) param.args[0];
                        int sortVal = (int) XposedHelpers.callMethod(param.args[1], "getValue");
                        if (position == 0) {
                            DouyuPlusConfig.setFollowAllSort(null, sortVal);
                            DouyuLog.d(TAG, "Saved follow all-tab sort=" + sortVal);
                        } else if (position == 1) {
                            DouyuPlusConfig.setFollowLiveSort(null, sortVal);
                            DouyuLog.d(TAG, "Saved follow live-tab sort=" + sortVal);
                        }
                    }
                });
            }

            final Class<?> liveVmCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.follow.p.live.page.myfollow.tab.LiveFollowTabViewModel",
                    classLoader
            );
            if (liveVmCls != null) {
                XposedBridge.hookAllConstructors(liveVmCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Context ctx = (param.args != null && param.args.length > 0 && param.args[0] instanceof Context)
                                ? (Context) param.args[0] : null;
                        int savedLiveSort = DouyuPlusConfig.getFollowLiveSort(ctx);
                        if (savedLiveSort != DouyuPlusConfig.DEFAULT_FOLLOW_SORT) {
                            setSortFieldSafe(param.thisObject, "l", savedLiveSort);
                        }
                    }
                });
            }

            final Class<?> allVmCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.follow.p.live.page.myfollow.tab.AllFollowViewModel",
                    classLoader
            );
            if (allVmCls != null) {
                XposedBridge.hookAllConstructors(allVmCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Context ctx = (param.args != null && param.args.length > 0 && param.args[0] instanceof Context)
                                ? (Context) param.args[0] : null;
                        int savedAllSort = DouyuPlusConfig.getFollowAllSort(ctx);
                        if (savedAllSort != DouyuPlusConfig.DEFAULT_FOLLOW_SORT) {
                            setSortFieldSafe(param.thisObject, "i", savedAllSort);
                        }
                    }
                });
            }

            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook follow sort persistence", t);
            return false;
        }
    }

    private static Object findFollowSortTypeByValue(Class<?> sortEnumCls, int targetValue) {
        if (sortEnumCls == null) return null;
        Object[] constants = sortEnumCls.getEnumConstants();
        if (constants == null) return null;
        for (Object c : constants) {
            try {
                int v = (int) XposedHelpers.callMethod(c, "getValue");
                if (v == targetValue) {
                    return c;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
