package dev.localapi.deepseek;

import android.app.job.JobParameters;
import android.app.job.JobService;

import org.json.JSONObject;

/**
 * Periodic background check. Posts a notification when a newer version is
 * published; the in-app dialog is shown the next time the app is opened.
 */
public class UpdateJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            try {
                if (Updater.remindersEnabled(this) && Updater.online(this)) {
                    JSONObject manifest = Updater.fetchManifest();
                    if (Updater.isNewer(this, manifest)) {
                        Updater.storePending(this, manifest.toString());
                        Updater.notifyUpdate(this, manifest);
                    }
                }
            } catch (Exception e) {
                retry = true;
            }
            jobFinished(params, retry);
        }, "update-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
