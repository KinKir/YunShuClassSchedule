package top.itning.yunshuclassschedule.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import retrofit2.Call;
import retrofit2.Response;
import top.itning.yunshuclassschedule.common.App;
import top.itning.yunshuclassschedule.common.ConstantPool;
import top.itning.yunshuclassschedule.entity.ClassSchedule;
import top.itning.yunshuclassschedule.entity.ClassScheduleDao;
import top.itning.yunshuclassschedule.entity.DaoSession;
import top.itning.yunshuclassschedule.entity.EventEntity;
import top.itning.yunshuclassschedule.http.CheckClassScheduleVersion;
import top.itning.yunshuclassschedule.http.DownloadClassSchedule;
import top.itning.yunshuclassschedule.util.HttpUtils;

/**
 * 课程表服务
 *
 * @author itning
 */
public class DataDownloadService extends Service {
    private static final String TAG = "DataDownloadService";
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        Log.d(TAG, "on Create");
        EventBus.getDefault().register(this);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        assert powerManager != null;
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ":DataDownloadService");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(20 * 60 * 1000);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "on Destroy");
        EventBus.getDefault().unregister(this);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.BACKGROUND, sticky = true)
    public void onMessageEvent(EventEntity eventEntity) {
        switch (eventEntity.getId()) {
            case START_CHECK_CLASS_SCHEDULE_UPDATE: {
                checkClassScheduleUpdate();
                EventBus.getDefault().removeStickyEvent(eventEntity);
                break;
            }
            default:
        }
    }

    /**
     * 检查课程表数据更新
     */
    private void checkClassScheduleUpdate() {
        String version = App.sharedPreferences.getString(ConstantPool.Str.USER_CLASS_ID.get(), "-1");
        if ("-1".equals(version)) {
            EventBus.getDefault().post(new EventEntity(ConstantPool.Int.END_CHECK_CLASS_SCHEDULE_UPDATE));
            return;
        }
        HttpUtils.getRetrofit().create(CheckClassScheduleVersion.class).checkVersion(version).enqueue(new retrofit2.Callback<String>() {

            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                if (response.code() == ConstantPool.Int.RESPONSE_SUCCESS_CODE.get()) {
                    String version = response.body();
                    //判断课程表数据版本与服务器是否一致
                    if (!App.sharedPreferences.getString(ConstantPool.Str.APP_CLASS_SCHEDULE_VERSION.get(), "").equals(version)) {
                        //不一致,更新版本,下载新数据
                        App.sharedPreferences.edit().putString(ConstantPool.Str.APP_CLASS_SCHEDULE_VERSION.get(), version).apply();
                        downloadClassSchedule();
                    } else {
                        EventBus.getDefault().post(new EventEntity(ConstantPool.Int.END_CHECK_CLASS_SCHEDULE_UPDATE));
                    }
                } else {
                    Log.e(TAG, "check class schedule error " + response.code());
                    //event to commonService
                    EventBus.getDefault().post(new EventEntity(ConstantPool.Int.HTTP_ERROR, "服务器连接失败:" + response.code()));
                    EventBus.getDefault().post(new EventEntity(ConstantPool.Int.END_CHECK_CLASS_SCHEDULE_UPDATE));
                }
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                Log.e(TAG, "check class schedule error ", t);
                //event to commonService
                EventBus.getDefault().post(new EventEntity(ConstantPool.Int.HTTP_ERROR, "服务器连接失败:" + t.toString()));
                EventBus.getDefault().post(new EventEntity(ConstantPool.Int.END_CHECK_CLASS_SCHEDULE_UPDATE));
            }
        });
    }

    /**
     * 下载课程表
     */
    private void downloadClassSchedule() {
        HttpUtils.getRetrofit().create(DownloadClassSchedule.class).download(App.sharedPreferences.getString(ConstantPool.Str.USER_CLASS_ID.get(), "-1")).enqueue(new retrofit2.Callback<List<ClassSchedule>>() {

            @Override
            public void onResponse(@NonNull Call<List<ClassSchedule>> call, @NonNull Response<List<ClassSchedule>> response) {
                if (response.code() == ConstantPool.Int.RESPONSE_SUCCESS_CODE.get()) {
                    List<ClassSchedule> classScheduleList = response.body();
                    if (classScheduleList != null) {
                        DaoSession daoSession = ((App) getApplication()).getDaoSession();
                        ClassScheduleDao classScheduleDao = daoSession.getClassScheduleDao();
                        classScheduleDao.deleteAll();
                        for (ClassSchedule classSchedule : classScheduleList) {
                            classScheduleDao.insert(classSchedule);
                        }
                    }
                    EventBus.getDefault().post(new EventEntity(ConstantPool.Int.END_CHECK_CLASS_SCHEDULE_UPDATE));
                } else {
                    Log.e(TAG, "download class schedule error :" + response.code());
                    //event to commonService
                    EventBus.getDefault().post(new EventEntity(ConstantPool.Int.HTTP_ERROR, "下载课表错误:" + response.code()));
                    EventBus.getDefault().post(new EventEntity(ConstantPool.Int.END_CHECK_CLASS_SCHEDULE_UPDATE));
                }
            }

            @Override
            public void onFailure(@NonNull Call<List<ClassSchedule>> call, @NonNull Throwable t) {
                Log.e(TAG, "download class schedule error ", t);
                //event to commonService
                EventBus.getDefault().post(new EventEntity(ConstantPool.Int.HTTP_ERROR, "下载课表错误:" + t.toString()));
                EventBus.getDefault().post(new EventEntity(ConstantPool.Int.END_CHECK_CLASS_SCHEDULE_UPDATE));
            }
        });
    }
}
