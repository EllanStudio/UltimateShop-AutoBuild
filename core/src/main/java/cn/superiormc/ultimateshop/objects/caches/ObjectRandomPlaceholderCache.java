package cn.superiormc.ultimateshop.objects.caches;

import cn.superiormc.ultimateshop.UltimateShop;
import cn.superiormc.ultimateshop.managers.BungeeCordManager;
import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.managers.ErrorManager;
import cn.superiormc.ultimateshop.objects.items.subobjects.ObjectRandomPlaceholder;
import cn.superiormc.ultimateshop.utils.CommandUtil;
import cn.superiormc.ultimateshop.utils.CommonUtil;
import cn.superiormc.ultimateshop.utils.SchedulerUtil;
import cn.superiormc.ultimateshop.utils.TextUtil;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ObjectRandomPlaceholderCache {

    private List<String> nowValue = null;

    private LocalDateTime refreshDoneTime = null;

    private SchedulerUtil resetTask;

    private boolean initialized;

    private final ObjectRandomPlaceholder placeholder;

    private final ObjectCache cache;

    public ObjectRandomPlaceholderCache(ObjectCache cache,
                                        ObjectRandomPlaceholder placeholder) {
        this.cache = cache;
        this.placeholder = placeholder;
    }

    public synchronized void initialize() {
        if (initialized || cache.canNotModify()) {
            return;
        }
        initialized = true;
        setRefreshTime();
    }

    public synchronized void loadState(List<String> value, LocalDateTime refreshTime) {
        if (value == null) {
            return;
        }
        cancelResetTask();
        this.nowValue = new ArrayList<>(value);
        this.refreshDoneTime = refreshTime;
        this.initialized = true;
        if (!cache.canNotModify()) {
            activateResetTask();
        }
    }

    public synchronized void activateResetTask() {
        if (!initialized || cache.canNotModify()) {
            return;
        }
        scheduleResetTask();
    }

    public ObjectRandomPlaceholder getPlaceholder() {
        return placeholder;
    }

    public synchronized LocalDateTime getRefreshDoneTime() {
        if (refreshDoneTime != null && !refreshDoneTime.isAfter(CommonUtil.getNowTime())) {
            setRefreshTime();
        }
        return refreshDoneTime;
    }

    /** Returns persisted state without refreshing or touching Bukkit scheduling. */
    public synchronized LocalDateTime getStoredRefreshDoneTime() {
        return refreshDoneTime;
    }

    public synchronized void removeRefreshDoneTime() {
        if (refreshDoneTime != null && !UltimateShop.freeVersion) {
            cache.markDirty(ObjectCache.DirtySection.RANDOM_PLACEHOLDERS);
        }
        refreshDoneTime = null;
    }

    public synchronized void cancelResetTask() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
    }

    public List<String> getNowValue() {
        return getNowValue(true, false);
    }

    public List<String> getNowValue(boolean disable) {
        return getNowValue(true, disable);
    }

    public synchronized List<String> getNowValue(boolean needRefresh, boolean disable) {
        if (needRefresh) {
            setRefreshTime(disable);
        }
        return nowValue == null ? null : new ArrayList<>(nowValue);
    }

    public synchronized void setRefreshTime() {
        setRefreshTime(false);
    }

    public synchronized void setRefreshTime(boolean notUseBungee) {
        if (cache.canNotModify()) {
            cancelResetTask();
            return;
        }
        String mode = placeholder.getMode();
        String time = TextUtil.withPAPI(placeholder.getConfig().getString("reset-time"), null);
        if (mode == null || time.isEmpty()) {
            if (nowValue == null) {
                setPlaceholder(notUseBungee);
            }
            return;
        }
        if (mode.equals("ONCE")) {
            setPlaceholder(notUseBungee);
            return;
        }
        boolean needRefresh = nowValue == null || refreshDoneTime == null || !refreshDoneTime.isAfter(CommonUtil.getNowTime());
        for (ObjectRandomPlaceholder tempVal1 : placeholder.getNotSameAs()) {
            if (tempVal1.equals(getPlaceholder())) {
                continue;
            }
            if (tempVal1.getNowValue(cache).equals(nowValue)) {
                needRefresh = true;
            }
        }
        if (needRefresh) {
            LocalDateTime previousRefreshDoneTime = refreshDoneTime;
            switch (mode) {
                case "TIMED":
                    refreshDoneTime = getTimedRefreshTime(time);
                    break;
                case "TIMER":
                    refreshDoneTime = getTimerRefreshTime(time);
                    break;
                case "CUSTOM":
                    refreshDoneTime = CommonUtil.stringToTime(time, placeholder.getConfig().getString("time-format", "yyyy-MM-dd HH:mm:ss"));
                    break;
                case "RANDOM_PLACEHOLDER":
                    if (time.equals(placeholder.getID())) {
                        refreshDoneTime = CommonUtil.getNowTime().withYear(2999);
                    } else {
                        refreshDoneTime = ObjectRandomPlaceholder.getRefreshDoneTimeObject(cache.getPlayer(), time);
                    }
                    break;
                default:
                    refreshDoneTime = CommonUtil.getNowTime().withYear(2999);
                    break;
            }
            if (!UltimateShop.freeVersion
                    && !java.util.Objects.equals(previousRefreshDoneTime, refreshDoneTime)) {
                cache.markDirty(ObjectCache.DirtySection.RANDOM_PLACEHOLDERS);
            }

            cancelResetTask();
            scheduleResetTask();
            setPlaceholder(notUseBungee);
            CommandUtil.updateGUI(cache.getPlayer());
        }
    }

    private void scheduleResetTask() {
        if (!ConfigManager.configManager.getBoolean("use-times.auto-reset-mode")
                || refreshDoneTime == null
                || refreshDoneTime.getYear() == 2999) {
            return;
        }

        long delayMillis = Duration.between(CommonUtil.getNowTime(), refreshDoneTime).toMillis();
        long delayTicks = Math.max(0, delayMillis / 50);
        resetTask = SchedulerUtil.runTaskLater(this::setRefreshTime, delayTicks + 20);
    }

    public void setPlaceholder(boolean notUseBungee) {
        setPlaceholder(placeholder.getNewValue(cache), notUseBungee);
    }

    public synchronized void setPlaceholder(List<String> element, boolean notUseBungee) {
        if (element == null || cache.canNotModify()) {
            return;
        }
        List<String> updatedValue = new ArrayList<>(element);
        if (!UltimateShop.freeVersion
                && !"ONCE".equals(placeholder.getMode())
                && !java.util.Objects.equals(nowValue, updatedValue)) {
            cache.markDirty(ObjectCache.DirtySection.RANDOM_PLACEHOLDERS);
        }
        nowValue = updatedValue;
        if (!notUseBungee && BungeeCordManager.bungeeCordManager != null) {
            BungeeCordManager.bungeeCordManager.sendToOtherServer(
                    placeholder.getID(),
                    CommonUtil.translateStringList(nowValue),
                    CommonUtil.timeToString(refreshDoneTime));
        }
    }

    private LocalDateTime getTimedRefreshTime(String time) {
        LocalDate nowTime = LocalDate.now();
        LocalDateTime refreshResult = null;
        String[] tempVal3 = time.split(";;");
        for (String tempVal4 : tempVal3) {
            LocalDateTime thisResult;
            String[] tempVal2 = tempVal4.split(":");
            if (tempVal2.length < 3) {
                ErrorManager.errorManager.sendErrorMessage("§cError: Your reset time " + tempVal4 + " is invalid.");
                return CommonUtil.getNowTime();
            }
            int month = 0;
            int day = 0;
            if (tempVal2.length == 5) {
                month = Integer.parseInt(tempVal2[0]);
            }
            if (tempVal2.length >= 4) {
                day = Integer.parseInt(tempVal2[tempVal2.length - 4]);
            }
            thisResult = nowTime.atTime(Integer.parseInt(tempVal2[tempVal2.length - 3]),
                    Integer.parseInt(tempVal2[tempVal2.length - 2]),
                    Integer.parseInt(tempVal2[tempVal2.length - 1]));
            thisResult= thisResult.plusDays(day).plusMonths(month);
            if (CommonUtil.getNowTime().isAfter(thisResult)) {
                thisResult = thisResult.plusDays(1L);
            }
            if (refreshResult == null || thisResult.isBefore(refreshResult)) {
                refreshResult = thisResult;
            }
        }
        return refreshResult;
    }

    private LocalDateTime getTimerRefreshTime(String time) {
        LocalDateTime refreshResult = CommonUtil.getNowTime();
        String[] tempVal2 = time.split(":");
        if (tempVal2.length < 3) {
            ErrorManager.errorManager.sendErrorMessage("§cError: Your reset time " + time + " is invalid.");
            return CommonUtil.getNowTime();
        }
        int month = 0;
        int day = 0;
        if (tempVal2.length == 5) {
            month = Integer.parseInt(tempVal2[0]);
        }
        if (tempVal2.length >= 4) {
            day = Integer.parseInt(tempVal2[tempVal2.length - 4]);
        }
        refreshResult = refreshResult.plusMonths(month).plusDays(day)
                .plusHours(Integer.parseInt(tempVal2[tempVal2.length - 3]))
                .plusMinutes(Integer.parseInt(tempVal2[tempVal2.length - 2]))
                .plusSeconds(Integer.parseInt(tempVal2[tempVal2.length - 1]));
        return refreshResult;
    }

}
