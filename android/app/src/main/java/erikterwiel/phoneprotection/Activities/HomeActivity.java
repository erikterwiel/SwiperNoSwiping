package erikterwiel.phoneprotection.Activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.DynamoDBMapper;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.getbase.floatingactionbutton.FloatingActionButton;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import erikterwiel.phoneprotection.Account;
import erikterwiel.phoneprotection.Phone;
import erikterwiel.phoneprotection.R;
import erikterwiel.phoneprotection.Services.DetectionService;
import erikterwiel.phoneprotection.Singletons.DynamoDB;
import erikterwiel.phoneprotection.Singletons.Protection;
import erikterwiel.phoneprotection.Singletons.Rekognition;
import erikterwiel.phoneprotection.Singletons.S3;
import erikterwiel.phoneprotection.User;

public class HomeActivity extends AppCompatActivity {

    private static final String TAG = "HomeActivity.java";
    private static final String BUCKET_NAME = "phoneprotectionpictures";
    private static final int REQUEST_PHONE = 102;
    private static final int REQUEST_ADD = 103;
    private static final int REQUEST_EDIT = 104;

    private AmazonS3Client mS3Client;
    private TransferUtility mTransferUtility;
    private DynamoDBMapper mMapper;
    private FusedLocationProviderClient mFusedLocationClient;
    private Account mAccount;
    private ArrayList<HashMap<String, Object>> mTransferRecordMaps = new ArrayList<>();
    private ArrayList<User> mUserList = new ArrayList<>();
    private ArrayList<Phone> mPhoneList = new ArrayList<>();
    private int mCompletedDownloads;
    private CoordinatorLayout mCoordinator;
    private RecyclerView mUsers;
    private RecyclerView mPhones;
    private UserAdapter mUserAdapter;
    private PhoneAdapter mPhoneAdapter;
    private Location mLocation;
    private Protection mProtection;
    private MenuItem mSettings;
    private LinearLayout mLoading;
    private Button mStart;
    private Button mStop;
    private FloatingActionButton mAdd;
    private FloatingActionButton mEdit;
    private int mPosition;
    private int mPhoneIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        Log.i(TAG, "onCreate() called");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        DynamoDB.init(this);
        S3.init(this);
        Rekognition.init(this);

        mMapper = DynamoDB.getInstance().getMapper();
        mS3Client = S3.getInstance().getS3Client();
        mTransferUtility = S3.getInstance().getTransferUtility();

        new DownloadPhone().execute();
        new DownloadUsers().execute();

        mCoordinator = findViewById(R.id.home_coordinator);
        mLoading = findViewById(R.id.home_loading_users);
        mStart = findViewById(R.id.home_start);
        mStop = findViewById(R.id.home_stop);
        mAdd = findViewById(R.id.home_new_user);
        mEdit = findViewById(R.id.home_edit_phone);

        mStart.setOnClickListener(view -> {
            mProtection.enableProtection();
            Snackbar.make(mCoordinator, "Protection enabled.", Snackbar.LENGTH_LONG).show();
        });

        mStop.setOnClickListener(view -> {
            mProtection.disableProtection();
            Snackbar.make(mCoordinator, "Protection disabled.", Snackbar.LENGTH_LONG).show();
        });

        mAdd.setOnClickListener(view -> {
            Intent addUserIntent = new Intent(HomeActivity.this, AddUserActivity.class);
            addUserIntent.putExtra("username", getIntent().getStringExtra("username"));
            startActivityForResult(addUserIntent, REQUEST_ADD);
        });

        mEdit.setOnClickListener(view -> {
            Intent editPhonesIntent = new Intent(HomeActivity.this, EditPhonesActivity.class);
            editPhonesIntent.putExtra("username", getIntent().getStringExtra("username"));
            startActivityForResult(editPhonesIntent, REQUEST_EDIT);
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.home_settings, menu);
        mSettings = menu.findItem(R.id.home_settings);
        mSettings.setOnMenuItemClickListener(onMenuItemClickListener -> {
            Intent settingsIntent = new Intent(HomeActivity.this, SettingsActivity.class);
            startActivity(settingsIntent);
            return false;
        });
        return super.onCreateOptionsMenu(menu);
    }

    public ItemTouchHelper.Callback createHelperCallBack() {
        ItemTouchHelper.SimpleCallback simpleCallback =  new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {

            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                                  RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(final RecyclerView.ViewHolder viewHolder, int swipeDir) {
                onItemRemove(viewHolder);
            }
        };
        return simpleCallback;
    }

    public void onItemRemove(RecyclerView.ViewHolder viewHolder) {
        int position = viewHolder.getAdapterPosition();
        Log.i(TAG, mUserList.get(position).getFileName());
        mPosition = position;
        new DeleteUser().execute();
        try {
            Thread.sleep(300);
            mUserList.remove(mPosition);
            mUserAdapter.notifyItemRemoved(mPosition);
        } catch (Exception ex) {}
    }

    private class DownloadPhone extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... inputs) {
            boolean seen = false;
            mAccount = mMapper.load(Account.class, getIntent().getStringExtra("username"));
            if (mAccount == null) {
                Intent addPhoneIntent = new Intent(HomeActivity.this, AddPhoneActivity.class);
                addPhoneIntent.putExtra("username", getIntent().getStringExtra("username"));
                startActivityForResult(addPhoneIntent, REQUEST_PHONE);
            } else {
                for (int i = 0; i < mAccount.getUniques().size(); i++) {
                    mPhoneList.add(new Phone(
                            mAccount.getUniques().get(i),
                            mAccount.getNames().get(i),
                            mAccount.getLatitudes().get(i),
                            mAccount.getLongitudes().get(i)));
                    if (mAccount.getUniques().get(i).equals(Settings.Secure.getString(getApplicationContext().getContentResolver(), Settings.Secure.ANDROID_ID))) {
                        mPhoneIndex = i;
                        seen = true;
                    }
                    if (i == mAccount.getUniques().size() - 1) {
                        if (!seen) {
                            Intent addPhoneIntent = new Intent(HomeActivity.this, AddPhoneActivity.class);
                            addPhoneIntent.putExtra("username", getIntent().getStringExtra("username"));
                            startActivityForResult(addPhoneIntent, REQUEST_PHONE);
                        }
                        initLocation();
                    }
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mPhones = findViewById(R.id.home_phones);
            mPhones.setLayoutManager(new LinearLayoutManager(HomeActivity.this));
            mPhoneAdapter = new PhoneAdapter(mPhoneList);
            mPhones.setAdapter(mPhoneAdapter);
        }
    }

    private void initLocation() {
        try {
            mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
            mFusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                if (location != null) {
                    mLocation = location;
                    new UpdatePhone().execute();
                }
            });
        } catch (SecurityException ex) {
            ex.printStackTrace();
        }

    }

    private class UpdatePhone extends AsyncTask<Void, Void, Void> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected Void doInBackground(Void... voids) {
            mAccount.replaceLatitude(mPhoneIndex, mLocation.getLatitude());
            mAccount.replaceLongitude(mPhoneIndex, mLocation.getLongitude());
            mMapper.save(mAccount);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Intent restartIntent = getIntent();
        finish();
        startActivity(restartIntent);
    }

    private class DeleteUser extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... inputs) {
            String[] filePathSplit = mUserList.get(mPosition).getFileName().split("/");
            String nameJpg = filePathSplit[filePathSplit.length - 2] + "/" + filePathSplit[filePathSplit.length - 1];
            Log.i(TAG, nameJpg);
            try {
                mS3Client.deleteObject(BUCKET_NAME, nameJpg);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return null;
        }
    }

    private class DownloadUsers extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... inputs) {
            ObjectListing objectListing = mS3Client.listObjects(BUCKET_NAME);
            List<S3ObjectSummary> s3ObjList = objectListing.getObjectSummaries();
            for (S3ObjectSummary summary : s3ObjList) {
                HashMap<String, Object> map = new HashMap<>();
                String key = summary.getKey();
                if (key.contains(getIntent().getStringExtra("username")) &&
                        !key.contains("Intruder")) {
                    map.put("key", key);
                    mTransferRecordMaps.add(map);
                }
            }
            for (int i = 0; i < mTransferRecordMaps.size(); i++) {
                beginDownload((String) mTransferRecordMaps.get(i).get("key"));
            }
            if (mTransferRecordMaps.isEmpty()) {
                Intent addUserIntent = new Intent(HomeActivity.this, AddUserActivity.class);
                addUserIntent.putExtra("username", getIntent().getStringExtra("username"));
                startActivityForResult(addUserIntent, REQUEST_ADD);
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            Protection.init(HomeActivity.this, getIntent(), mUserList);
            mProtection = Protection.getInstance();
        }
    }

    public void beginDownload(String key) {
        File folder = new File("sdcard/Pictures/PhoneProtection/Input");
        if (!folder.exists()) folder.mkdir();
        File file = new File(folder, key);
        TransferObserver observer = mTransferUtility.download(BUCKET_NAME, key, file);
        observer.setTransferListener(new DownloadListener());

        User user = new User();
        String filePath = file.getAbsolutePath();
        String[] filePathSplit = filePath.split("/");
        String nameJpg = filePathSplit[filePathSplit.length - 1];
        String name = nameJpg.substring(0, nameJpg.length() - 4);
        user.setFileName(filePath);
        user.setName(name);
        mUserList.add(user);
        Log.i(TAG, user.getFileName());
        Log.i(TAG, user.getName());
    }

    private class DownloadListener implements TransferListener {
        @Override
        public void onStateChanged(int id, TransferState state) {
            Log.i(TAG, state + "");
            if (state == TransferState.COMPLETED) {
                mCompletedDownloads += 1;
                if (mCompletedDownloads == mUserList.size()) displayList();
            }
        }
        @Override
        public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
            if (bytesTotal != 0) {
                int percentage = (int) (bytesCurrent / bytesTotal * 100);
                Log.i(TAG, Integer.toString(percentage) + "% downloaded");
            }
        }
        @Override
        public void onError(int id, Exception ex) {
            ex.printStackTrace();
            Log.i(TAG, "Error detected");
        }
    }

    public void displayList() {
        for (int i = 0; i < mUserList.size(); i++) {
            Bitmap bitmap = BitmapFactory.decodeFile(mUserList.get(i).getFileName());
            mUserList.get(i).setImage(bitmap);
        }
        ViewGroup.LayoutParams params = mLoading.getLayoutParams();
        params.width = 0;
        params.height = 0;
        mLoading.setVisibility(View.GONE);
        mLoading.setLayoutParams(params);
        mUsers = findViewById(R.id.home_users);
        mUsers.setLayoutManager(new LinearLayoutManager(this));
        mUserAdapter = new UserAdapter(mUserList);
        mUsers.setAdapter(mUserAdapter);
        ItemTouchHelper itemTouchHelper= new ItemTouchHelper(createHelperCallBack());
        itemTouchHelper.attachToRecyclerView(mUsers);
    }

    private class UserAdapter extends RecyclerView.Adapter<UserHolder> {
        private ArrayList<User> userList;

        public UserAdapter(ArrayList<User> incomingList) {
            userList = incomingList;
        }

        @Override
        public UserHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater layoutInflater = LayoutInflater.from(HomeActivity.this);
            View view = layoutInflater.inflate(R.layout.item_user, parent, false);
            return new HomeActivity.UserHolder(view);
        }

        @Override
        public void onBindViewHolder(UserHolder holder, int position) {
            User user = userList.get(position);
            holder.bindUser(user);
        }

        @Override
        public int getItemCount() {
            return userList.size();
        }
    }

    private class UserHolder extends RecyclerView.ViewHolder {
        private User mUser;
        private ImageView mImage;
        private TextView mName;

        public UserHolder(View itemView) {
            super(itemView);
            mImage = itemView.findViewById(R.id.user_image);
            mName = itemView.findViewById(R.id.user_name);
        }

        public void bindUser(User user) {
            mUser = user;
            mImage.setImageBitmap(Bitmap.createScaledBitmap(
                    mUser.getImage(),180, 240, false));
            mName.setText(mUser.getName());
        }
    }

    private class PhoneAdapter extends RecyclerView.Adapter<PhoneHolder> {
        private ArrayList<Phone> phoneList;

        public PhoneAdapter(ArrayList<Phone> incomingList) {
            phoneList = incomingList;
        }

        @Override
        public PhoneHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater layoutInflater = LayoutInflater.from(HomeActivity.this);
            View view = layoutInflater.inflate(R.layout.item_phone, parent, false);
            return new HomeActivity.PhoneHolder(view);
        }

        @Override
        public void onBindViewHolder(PhoneHolder holder, int position) {
            Phone phone = phoneList.get(position);
            holder.bindPhone(phone);
        }

        @Override
        public int getItemCount() {
            return phoneList.size();
        }
    }

    private class PhoneHolder extends RecyclerView.ViewHolder {
        private Phone mPhone;
        private TextView mName;
        private TextView mLatitude;
        private TextView mLongitude;

        public PhoneHolder(View itemView) {
            super(itemView);
            mName = itemView.findViewById(R.id.phone_name);
            mLatitude = itemView.findViewById(R.id.phone_latitude);
            mLongitude = itemView.findViewById(R.id.phone_longitude);
        }

        public void bindPhone(Phone phone) {
            mPhone = phone;
            mName.setText(mPhone.getName());
            mLatitude.setText("Latitude: " + mPhone.getLatitude());
            mLongitude.setText("Longitude: " + mPhone.getLongitude());
        }
    }
}