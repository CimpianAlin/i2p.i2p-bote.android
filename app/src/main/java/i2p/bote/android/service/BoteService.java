package i2p.bote.android.service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import javax.mail.MessagingException;

import net.i2p.android.router.service.IRouterState;
import net.i2p.android.router.service.IRouterStateCallback;
import net.i2p.android.router.service.State;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterLaunch;
import i2p.bote.I2PBote;
import i2p.bote.android.EmailListActivity;
import i2p.bote.android.R;
import i2p.bote.android.ViewEmailActivity;
import i2p.bote.android.service.Init.RouterChoice;
import i2p.bote.android.util.BoteHelper;
import i2p.bote.email.Email;
import i2p.bote.fileencryption.PasswordException;
import i2p.bote.folder.EmailFolder;
import i2p.bote.folder.NewEmailListener;
import i2p.bote.network.NetworkStatusListener;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;

public class BoteService extends Service implements NetworkStatusListener, NewEmailListener {
    public static final String ROUTER_CHOICE = "router_choice";
    public static final int NOTIF_ID_SERVICE = 8073;
    public static final int NOTIF_ID_NEW_EMAIL = 80739047;

    RouterChoice mRouterChoice;
    NotificationCompat.Builder mStatusBuilder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mRouterChoice = (RouterChoice) intent.getSerializableExtra(ROUTER_CHOICE);
        if (mRouterChoice == RouterChoice.INTERNAL)
            new Thread(new RouterStarter()).start();

        I2PBote bote = I2PBote.getInstance();
        bote.startUp();
        bote.addNewEmailListener(this);

        if (mRouterChoice == RouterChoice.ANDROID) {
            // Bind to I2P Android
            Intent i2pIntent = new Intent(IRouterState.class.getName());
            mTriedBindState = bindService(
                    i2pIntent, mStateConnection, 0);
        } else if (mRouterChoice == RouterChoice.REMOTE)
            bote.connectNow();

        mStatusBuilder = new NotificationCompat.Builder(this)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setSmallIcon(R.drawable.ic_notif)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        Intent ni = new Intent(this, EmailListActivity.class);
        ni.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ni, PendingIntent.FLAG_UPDATE_CURRENT);
        mStatusBuilder.setContentIntent(pi);

        updateServiceNotifText();

        startForeground(NOTIF_ID_SERVICE, mStatusBuilder.build());

        bote.addNetworkStatusListener(this);

        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mTriedBindState) {
            try {
                mStateService.unregisterCallback(mStatusListener);
            } catch (RemoteException e) {}
            unbindService(mStateConnection);
        }
        mTriedBindState = false;

        I2PBote.getInstance().removeNetworkStatusListener(this);
        I2PBote.getInstance().removeNewEmailListener(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                I2PBote.getInstance().shutDown();
            }
        }).start();

        if (mRouterChoice == RouterChoice.INTERNAL)
            new Thread(new RouterStopper()).start();
    }


    //
    // Internal router helpers
    //

    private RouterContext mRouterContext;

    private class RouterStarter implements Runnable {
        public void run() {
            RouterLaunch.main(null);
            List<RouterContext> contexts = RouterContext.listContexts();
            mRouterContext = contexts.get(0);
            mRouterContext.router().setKillVMOnEnd(false);
        }
    }

    private class RouterStopper implements Runnable {
        public void run() {
            RouterContext ctx = mRouterContext;
            if (ctx != null)
                ctx.router().shutdown(Router.EXIT_HARD);
        }
    }


    //
    // I2P Android helpers
    //

    private IRouterState mStateService = null;
    private boolean mTriedBindState;
    private ServiceConnection mStateConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            mStateService = IRouterState.Stub.asInterface(service);
            try {
                mStateService.registerCallback(mStatusListener);
                final int state = mStateService.getState();
                if (state == State.ACTIVE)
                    I2PBote.getInstance().connectNow();
            } catch (RemoteException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            mStateService = null;
        }
    };

    private final IRouterStateCallback.Stub mStatusListener =
            new IRouterStateCallback.Stub() {
        public void stateChanged(int newState) throws RemoteException {
            if (newState == State.STOPPING ||
                    newState == State.MANUAL_STOPPING ||
                    newState == State.MANUAL_QUITTING ||
                    newState == State.NETWORK_STOPPING)
                stopSelf();
        }
    };


    // NetworkStatusListener

    @Override
    public void networkStatusChanged() {
        updateServiceNotifText();

        startForeground(NOTIF_ID_SERVICE, mStatusBuilder.build());
    }

    private void updateServiceNotifText() {
        String statusText;
        switch (I2PBote.getInstance().getNetworkStatus()) {
            case DELAY:
                statusText = getResources().getString(R.string.waiting_for_network);
                break;
            case CONNECTING:
                statusText = getResources().getString(R.string.connecting_to_network);
                break;
            case CONNECTED:
                statusText = getResources().getString(R.string.connected_to_network);
                break;
            case ERROR:
                statusText = getResources().getString(R.string.error);
                break;
            case NOT_STARTED:
            default:
                statusText = getResources().getString(R.string.not_started);
        }
        mStatusBuilder.setContentText(statusText);
    }

    // NewEmailListener

    @Override
    public void emailReceived(String messageId) {
        NotificationManager nm = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);

        NotificationCompat.Builder b =
                new NotificationCompat.Builder(this)
                .setAutoCancel(true);

        try {
            EmailFolder inbox = I2PBote.getInstance().getInbox();

            // Set the new email as \Recent
            inbox.setRecent(messageId, true);

            // Now display/update notification with all \Recent emails
            List<Email> newEmails = BoteHelper.getRecentEmails(inbox);
            int numNew = newEmails.size();
            switch (numNew) {
            case 0:
                nm.cancel(NOTIF_ID_NEW_EMAIL);
                return;

            case 1:
                Email email = newEmails.get(0);

                Bitmap picture = BoteHelper.getPictureForAddress(email.getOneFromAddress());
                if (picture != null)
                    b.setLargeIcon(picture);
                else
                    b.setSmallIcon(R.drawable.ic_contact_picture);

                b.setContentTitle(BoteHelper.getNameAndShortDestination(
                        email.getOneFromAddress()));
                b.setContentText(email.getSubject());

                Intent vei = new Intent(this, ViewEmailActivity.class);
                vei.putExtra(ViewEmailActivity.FOLDER_NAME, inbox.getName());
                vei.putExtra(ViewEmailActivity.MESSAGE_ID, email.getMessageID());
                vei.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pvei = PendingIntent.getActivity(this, 0, vei, PendingIntent.FLAG_UPDATE_CURRENT);
                b.setContentIntent(pvei);
                break;

            default:
                b.setSmallIcon(R.drawable.ic_notif);
                b.setContentTitle(getResources().getQuantityString(
                        R.plurals.n_new_emails, numNew, numNew));

                String bigText = "";
                for (Email ne : newEmails) {
                    bigText += BoteHelper.getNameAndShortDestination(
                            ne.getOneFromAddress());
                    bigText += ": " + ne.getSubject() + "\n";
                }
                b.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText));

                Intent eli = new Intent(this, EmailListActivity.class);
                eli.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent peli = PendingIntent.getActivity(this, 0, eli, PendingIntent.FLAG_UPDATE_CURRENT);
                b.setContentIntent(peli);
            }
        } catch (PasswordException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (MessagingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (GeneralSecurityException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        nm.notify(NOTIF_ID_NEW_EMAIL, b.build());
    }
}
