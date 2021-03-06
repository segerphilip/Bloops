package website.bloop.app.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.games.Games;
import com.google.android.gms.games.Player;
import com.google.example.games.basegameutils.BaseGameUtils;
import com.google.firebase.iid.FirebaseInstanceId;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import website.bloop.app.BloopApplication;
import website.bloop.app.BloopFirebaseInstanceIDService;
import website.bloop.app.R;
import website.bloop.app.views.SonarView;

import static website.bloop.app.BloopApplication.BLOOP_PREFERENCE_FILE;

/**
 * Login authentication through Google Play Games
 * Reference: https://developers.google.com/games/services/training/signin
 * First start reference: https://github.com/PaoloRotolo/AppIntro/wiki/How-to-Use#show-the-intro-once
 */
public class PlayLoginActivity extends AppCompatActivity
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String PREF_LOGIN = "LoginPREF";
    private static final String PREF_LOGIN_VAL = "relogin";
    private static final String TAG = "PlayLogin";

    private static int RC_SIGN_IN = 9001;

    private boolean mResolvingConnectionFailure = false;
    private boolean mAutoStartSignInFlow = false;
    private boolean mSignInClicked = false;

    private GoogleApiClient mGoogleApiClient;
    private BloopApplication mApplication;

    @BindView(R.id.signInName)
    TextView loginText;

    @BindView(R.id.signInButton)
    Button loginButton;

    @BindView(R.id.sonarView)
    SonarView bloopView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play_login);

        if (!TutorialActivity.hasTutorialRun(this)) {
            Intent intent = new Intent(this, TutorialActivity.class);
            startActivity(intent);
            finish();
        }

        ButterKnife.bind(this);

        // create the Google Api Client with access to the Play Games services
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Games.API).addScope(Games.SCOPE_GAMES)
                .build();

        // set to application (like singleton) so we can re-call it
        BloopApplication.getInstance().setGoogleApiClient(mGoogleApiClient);

        mApplication = BloopApplication.getInstance();
        SharedPreferences loginPref = getSharedPreferences(PREF_LOGIN, Context.MODE_PRIVATE);
        boolean loggedIn = loginPref.getBoolean(PREF_LOGIN_VAL, false);

        if (loggedIn) {
            mGoogleApiClient.connect();
        }

        loginButton.setOnClickListener(view -> signInClicked());
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Player p = Games.Players.getCurrentPlayer(mGoogleApiClient);
        String displayName;
        String playerId;

        if (p == null) {
            Log.w(TAG, "mGamesClient.getCurrentPlayer() is NULL!");
            displayName = "???";
        } else {
            displayName = p.getDisplayName();

            playerId = Games.Players.getCurrentPlayerId(mGoogleApiClient);

            mApplication.setPlayerName(displayName);
            mApplication.setPlayerId(playerId);

            website.bloop.app.api.Player player = new website.bloop.app.api.Player(displayName, playerId, null);
            addUser(player);
        }

        // hide button on login
        loginButton.setVisibility(View.INVISIBLE);
        loginText.setText(displayName);

        // store that we are logged in
        SharedPreferences pref = getSharedPreferences(PREF_LOGIN, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = pref.edit();
        ed.putBoolean(PREF_LOGIN_VAL, true);
        ed.apply();

        // start the main game now
        Intent newIntent = new Intent(getBaseContext(), BloopActivity.class);
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(newIntent);
        finish();
    }

    private void addUser(website.bloop.app.api.Player player) {
        SharedPreferences prefs = getSharedPreferences(BLOOP_PREFERENCE_FILE, Context.MODE_PRIVATE);
        mApplication.getService().addPlayer(player)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        responseBody -> {
                            String token = prefs.getString(BloopFirebaseInstanceIDService.PREF_FIREBASE_TOKEN, "");
                            if (token.equals("")) {
                                token = FirebaseInstanceId.getInstance().getToken();
                            }
                            mApplication.sendFirebaseRegistrationToServer(token);
                        },
                        throwable -> Log.e(TAG, throwable.getMessage())
                );
    }

    @Override
    public void onConnectionSuspended(int i) {
        // Attempt to reconnect
        mGoogleApiClient.connect();
    }

    /**
     * Attempt to reconnect to play services, or display error if connection fails.
     *
     * @param connectionResult
     */
    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        if (mResolvingConnectionFailure) {
            // already resolving
            return;
        }

        // if the sign-in button was clicked or if auto sign-in is enabled,
        // launch the sign-in flow
        if (mSignInClicked || mAutoStartSignInFlow) {
            mAutoStartSignInFlow = false;
            mSignInClicked = false;
            mResolvingConnectionFailure = true;

            // Attempt to resolve the connection failure using BaseGameUtils.
            // The R.string.signin_other_error value should reference a generic
            // error string in your strings.xml file, such as "There was
            // an issue with sign-in, please try again later."
            if (!BaseGameUtils.resolveConnectionFailure(this,
                    mGoogleApiClient, connectionResult,
                    RC_SIGN_IN, String.valueOf(R.string.signin_other_error))) {
                mResolvingConnectionFailure = false;
            }
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == RC_SIGN_IN) {
            mSignInClicked = true;
            mResolvingConnectionFailure = false;
            if (resultCode == RESULT_OK) {
                mGoogleApiClient.connect();
            } else {
                // Bring up an error dialog to alert the user that sign-in
                // failed. The R.string.signin_failure should reference an error
                // string in your strings.xml file that tells the user they
                // could not be signed in, such as "Unable to sign in."
                BaseGameUtils.showActivityResultError(this,
                        requestCode, resultCode, R.string.signin_failure);
            }
        }
    }

    /**
     * Call when the sign-in button is clicked.
     */
    public void signInClicked() {
        bloopView.bloop();
        mSignInClicked = true;
        mGoogleApiClient.connect();
    }
}
