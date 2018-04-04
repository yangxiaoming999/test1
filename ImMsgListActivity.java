package com.test.yanxiu.im_ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.lzy.imagepicker.ImagePicker;
import com.lzy.imagepicker.bean.ImageItem;
import com.lzy.imagepicker.ui.ImageGridActivity;
import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.storage.Configuration;
import com.qiniu.android.storage.UpCancellationSignal;
import com.qiniu.android.storage.UpCompletionHandler;
import com.qiniu.android.storage.UpProgressHandler;
import com.qiniu.android.storage.UploadManager;
import com.qiniu.android.storage.UploadOptions;
import com.test.yanxiu.common_base.ui.KeyboardChangeListener;
import com.test.yanxiu.common_base.utils.SharedSingleton;
import com.test.yanxiu.common_base.utils.SrtLogger;
import com.test.yanxiu.common_base.utils.permission.OnPermissionCallback;
import com.test.yanxiu.common_base.utils.talkingdata.EventUpdate;
import com.test.yanxiu.faceshow_ui_base.ImBaseActivity;
import com.test.yanxiu.faceshow_ui_base.imagePicker.GlideImageLoader;
import com.test.yanxiu.im_core.RequestQueueHelper;
import com.test.yanxiu.im_core.db.DbMember;
import com.test.yanxiu.im_core.db.DbMsg;
import com.test.yanxiu.im_core.db.DbMyMsg;
import com.test.yanxiu.im_core.db.DbTopic;
import com.test.yanxiu.im_core.dealer.DatabaseDealer;
import com.test.yanxiu.im_core.dealer.MqttProtobufDealer;
import com.test.yanxiu.im_core.http.GetQiNiuTokenRequest;
import com.test.yanxiu.im_core.http.GetQiNiuTokenResponse;
import com.test.yanxiu.im_core.http.GetTopicMsgsRequest;
import com.test.yanxiu.im_core.http.GetTopicMsgsResponse;
import com.test.yanxiu.im_core.http.SaveImageMsgRequest;
import com.test.yanxiu.im_core.http.SaveImageMsgResponse;
import com.test.yanxiu.im_core.http.SaveTextMsgRequest;
import com.test.yanxiu.im_core.http.SaveTextMsgResponse;
import com.test.yanxiu.im_core.http.TopicCreateTopicRequest;
import com.test.yanxiu.im_core.http.TopicCreateTopicResponse;
import com.test.yanxiu.im_core.http.TopicGetTopicsRequest;
import com.test.yanxiu.im_core.http.TopicGetTopicsResponse;
import com.test.yanxiu.im_core.http.common.ImMsg;
import com.test.yanxiu.im_core.http.common.ImTopic;
import com.test.yanxiu.im_ui.callback.OnNaviLeftBackCallback;
import com.test.yanxiu.im_ui.callback.OnPullToRefreshCallback;
import com.test.yanxiu.im_ui.callback.OnRecyclerViewItemClickCallback;
import com.test.yanxiu.im_ui.view.ChoosePicsDialog;
import com.test.yanxiu.im_ui.view.RecyclerViewPullToRefreshHelper;
import com.test.yanxiu.network.HttpCallback;
import com.test.yanxiu.network.RequestBase;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import top.zibin.luban.Luban;
import top.zibin.luban.OnCompressListener;

public class ImMsgListActivity extends ImBaseActivity {
    private final String TAG = getClass().getSimpleName();

//    private DbTopic topic;


    private DbTopic topic;
    private static final int IMAGE_PICKER = 0x03;
    private static final int REQUEST_CODE_SELECT = 0x04;
    private ImTitleLayout mTitleLayout;
    private RecyclerView mMsgListRecyclerView;
    private MsgListAdapter mMsgListAdapter;
    private RecyclerViewPullToRefreshHelper ptrHelper;
    private EditText mMsgEditText;

    private long memberId = -1;
    private String memberName = null;
    private long fromTopicId = -1;
    private String mQiniuToken;
    private boolean mKeyBoardShown;//键盘已经显示了

    /**
     * 最新的成员列表
     * 由于存在移除成员的消息，为了对成员存在性进行判断
     * */
    private List<ImTopic.Member> memberList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        memberId = getIntent().getLongExtra(Constants.kCreateTopicMemberId, -1);
        memberName = getIntent().getStringExtra(Constants.kCreateTopicMemberName);
        fromTopicId = getIntent().getLongExtra(Constants.kFromTopicId, -1);

        setResult(RESULT_CANCELED); // 只为有返回，code无意义

        topic = SharedSingleton.getInstance().get(Constants.kShareTopic);
        if ((topic == null) || (topic.mergedMsgs.size() == 0)) {
            hasMoreMsgs = false;
        }


        setContentView(R.layout.activity_msg_list);
        setupView();
        setupData();
        initImagePicker();
        EventBus.getDefault().register(this);
    }

    /**
     *
     * 为了埋点
     * */
    private boolean isPrivatePage=false;
    @Override
    protected void onResume() {
        super.onResume();

//        埋点
        if (topic != null) {
            //  判断topic type
            if (topic.getType().equals("1")) {
                isPrivatePage=true;
               EventUpdate.onPrivatePageStart(this);
            }else if (topic.getType().equals("2")){
                //群聊
                isPrivatePage=false;
               EventUpdate.onGroupPageStart(this);
            }
        }else {
            //topic 为空 确定为私聊
            isPrivatePage=true;
           EventUpdate.onPrivatePageStart(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isPrivatePage) {
            EventUpdate.onPrivatePageEnd(this);
        }else {
            EventUpdate.onGroupPageEnd(this);
        }
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    private void hideSoftInput(EditText editText) {
        InputMethodManager inputMethodManager= (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(editText.getWindowToken(),0);
    }
    private void setupView() {
        mTitleLayout = findViewById(R.id.title_layout);
        mTitleLayout.setTitle("");

        mTitleLayout.setOnNaviLeftBackCallback(new OnNaviLeftBackCallback() {
            @Override
            public void onNaviBack() {
                //收起软键盘
                hideSoftInput(mMsgEditText);
                finish();
            }
        });

        if (topic == null) {
            mTitleLayout.setTitle(memberName);
        } else {
            mTitleLayout.setTitle(DatabaseDealer.getTopicTitle(topic, Constants.imId));
        }

        mMsgListRecyclerView = findViewById(R.id.msg_list_recyclerview);
        mMsgListRecyclerView.setLayoutManager(new FoucsLinearLayoutManager(this,
                LinearLayoutManager.VERTICAL,
                false));
        mMsgListAdapter = new MsgListAdapter(this);
        mMsgListAdapter.setTopic(topic);
        mMsgListRecyclerView.setAdapter(mMsgListAdapter);

        if (topic != null) {
            mMsgListAdapter.setmDatas(topic.mergedMsgs);
        } else {
            mMsgListAdapter.setmDatas(new ArrayList<DbMsg>());
        }

        mMsgListAdapter.notifyDataSetChanged();

        mMsgListAdapter.setmOnItemClickCallback(onDbMsgCallback);
        mMsgListRecyclerView.post(new Runnable() {
            @Override
            public void run() {
                if (mMsgListRecyclerView.getAdapter().getItemCount() > 1) {
                    mMsgListRecyclerView.scrollToPosition(mMsgListRecyclerView.getAdapter().getItemCount() - 1);//滚动到底部
                }
            }
        });

        ImageView mTakePicImageView = findViewById(R.id.takepic_imageview);
        mTakePicImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //发送照片入口
                //事件统计  点击聊聊相机
                EventUpdate.onClickMsgCameraEvent(ImMsgListActivity.this);
//                showChoosePicsDialog();

            }
        });

        mMsgEditText = findViewById(R.id.msg_edittext);
        mMsgEditText.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mMsgEditText.setRawInputType(InputType.TYPE_CLASS_TEXT);
        mMsgEditText.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if ((keyCode == event.KEYCODE_ENTER) && (event.getAction() == KeyEvent.ACTION_UP)) {
                    SrtLogger.log("imui", "TBD: 发送");
                    //统计
                    EventUpdate.onClickMsgSendEvent(ImMsgListActivity.this);
                    String msg = mMsgEditText.getText().toString();
                    mMsgEditText.setText("");
                    String trimMsg = msg.trim();
                    if (trimMsg.length() == 0) {
                        return true;
                    }

                    doSend(msg, null);

                    return true;
                }
                return false;
            }
        });
        //新增的 发送按钮 发送逻辑与 按键发送一样
        final TextView sendTv=findViewById(R.id.tv_sure);
        sendTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SrtLogger.log("imui", "TBD: 发送");
                //统计
                EventUpdate.onClickMsgSendEvent(ImMsgListActivity.this);
                String msg = mMsgEditText.getText().toString();
                mMsgEditText.setText("");
                String trimMsg = msg.trim();
                if (trimMsg.length() == 0) {
                    return ;
                }
                doSend(msg, null);
            }
        });
        //添加监听 当有文字输入时 展示发送按钮可点击
        mMsgEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                if (charSequence != null) {
                    if (charSequence.length()>0) {
                        //设置 enable 可点击状态
                        sendTv.setEnabled(true);
                        sendTv.setBackgroundResource(R.drawable.im_sendbtn_default);
                        return;
                    }
                }
                //设置颜色为 disable 与 按下颜色一样
                sendTv.setEnabled(false);
                sendTv.setBackgroundResource(R.drawable.im_sendbtn_pressed);
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });
        //初始设置为 不可点击 当用户有输入 才进行使能设置
        sendTv.setEnabled(false);
        sendTv.setBackgroundResource(R.drawable.im_sendbtn_pressed);

        // 弹出键盘后处理
        KeyboardChangeListener keyboardListener = new KeyboardChangeListener(this);
        keyboardListener.setKeyBoardListener(new KeyboardChangeListener.KeyBoardListener() {
            @Override
            public void onKeyboardChange(boolean isShow, int keyboardHeight) {
                mKeyBoardShown = isShow;
                if ((isShow) && (mMsgListRecyclerView.getAdapter().getItemCount() > 1)) {
                    mMsgListRecyclerView.scrollToPosition(mMsgListRecyclerView.getAdapter().getItemCount() - 1);//滚动到底部
                }
            }
        });

        // pull to refresh，由于覆盖了OnTouchListener，所以需要在这里处理点击外部键盘收起
        ptrHelper = new RecyclerViewPullToRefreshHelper(this, mMsgListRecyclerView, new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (mKeyBoardShown) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(ImMsgListActivity.this.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
                    mMsgListRecyclerView.clearFocus();
                }
                return false;
            }
        });
        ptrHelper.setmCallback(mOnLoadMoreCallback);
    }

    @Subscribe
    public void onTopicUpdate(MqttProtobufDealer.TopicUpdateEvent event) {
        mMsgListAdapter.setmDatas(topic.mergedMsgs);
        mMsgListAdapter.notifyDataSetChanged();
        mMsgListRecyclerView.scrollToPosition(mMsgListAdapter.getItemCount() - 1);
    }
    private void setupData() {
        if (topic != null&&!DatabaseDealer.isMockTopic(topic)) {
            // 每次进入话题更新用户信息
            updateTopicFromHttp(topic.getTopicId() + "");
        }
    }

    private void updateTopicFromHttp(final String topicId) {
        // http, mqtt 公用
        TopicGetTopicsRequest getTopicsRequest = new TopicGetTopicsRequest();
        getTopicsRequest.imToken = Constants.imToken;
        getTopicsRequest.topicIds = topicId;

        getTopicsRequest.startRequest(TopicGetTopicsResponse.class, new HttpCallback<TopicGetTopicsResponse>() {
            @Override
            public void onSuccess(RequestBase request, TopicGetTopicsResponse ret) {
                //正确的长度 为1
                if (ret.code==0) {
                    //当 用户被移除 目标群组时 data=null
                    if (ret.data == null||ret.data.topic==null) {
                        Toast.makeText(ImMsgListActivity.this,"【已被移出此班】",Toast.LENGTH_SHORT).show();
                        return ;
                    }
                    for (ImTopic imTopic : ret.data.topic) {
                        //更新数据库 topic 信息
                        DbTopic dbTopic = DatabaseDealer.updateDbTopicWithImTopic(imTopic);
                        dbTopic.latestMsgTime = imTopic.latestMsgTime;
                        dbTopic.latestMsgId = imTopic.latestMsgId;
                        //请求成功 消除红点
                        dbTopic.setShowDot(false);
                        dbTopic.save();
                        //保证 topic 的持有
                        topic.setName(dbTopic.getName());
                        topic.setChange(dbTopic.getChange());
                        topic.setGroup(dbTopic.getGroup());
                        topic.setType(dbTopic.getType());
                        topic.setTopicId(dbTopic.getTopicId());
                        topic.latestMsgId = dbTopic.latestMsgId;
                        topic.latestMsgTime = dbTopic.latestMsgTime;
                        topic.setShowDot(dbTopic.isShowDot());
                        //member 持有的更新
                        topic.setMembers(dbTopic.getMembers());
                        //对私聊topic 的 title 进行修正
                        if (topic.getType().equals("1")) {
                            for (DbMember member : topic.getMembers()) {
                                if ( member.getImId()!= Constants.imId) {
                                    //如果 是 由联系人界面跳转进来  需要通知联系人界面进行信息更新
                                    EventBus.getDefault().post(new MemberInfoUpdateEvent(member.getImId(),member.getName(),member.getAvatar()));
                                    mTitleLayout.setTitle(member.getName());
                                }
                            }
                        }
                    }
                    //使用最新的 成员信息 并对群聊情况下的 title 进行更新
                    if (topic.getType().equals("2")) {
//                        mTitleLayout.setTitle("班级群聊 (" + topic.getMembers().size() + ")");
                        mTitleLayout.setTitle("班级群聊 (" + ret.data.topic.get(0).members.size() + ")");
                    }
                    //持有最新的成员列表
                    memberList=ret.data.topic.get(0).members;
                    mMsgListAdapter.setRemainMemberList(memberList);
                    mMsgListAdapter.notifyDataSetChanged();
                }

            }

            @Override
            public void onFail(RequestBase request, Error error) {

            }
        });

    }

    private RequestQueueHelper httpQueueHelper = new RequestQueueHelper();

    public class NewTopicCreatedEvent {
        public DbTopic dbTopic;
    }

    public class MockTopicRemovedEvent {
        public DbTopic dbTopic;
    }


    /**
     *
     * 用于传递 member消息更新的类
     * 发送者是 ImMsgListActivity
     * 接受者是 ContactsFragment
     * */
    public static class MemberInfoUpdateEvent implements Serializable{
        public MemberInfoUpdateEvent(long imId,String name, String avatar) {
            this.name = name;
            this.avatar = avatar;
            this.imId=imId;
        }

        /**
         * member id
         * */
        long imId;
        /**
         * member name
         * */
        String name;
        /**
         * member 头像
         * */
        String avatar;


        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAvatar() {
            return avatar;
        }

        public void setAvatar(String avatar) {
            this.avatar = avatar;
        }
    }

    private void doSendMsg(final String msg, final String reqId) {
        SaveTextMsgRequest saveTextMsgRequest = new SaveTextMsgRequest();
        saveTextMsgRequest.imToken = Constants.imToken;
        saveTextMsgRequest.topicId = Long.toString(topic.getTopicId());
        saveTextMsgRequest.msg = msg;

        if (reqId != null) {
            // resend需要走相同的路径，但是msg已经有reqId了
            saveTextMsgRequest.reqId = reqId;
        }

        // 我发送的必然已经存在于队列
        DbMyMsg sendMsg = null;
        for (DbMsg m : topic.mergedMsgs) {
            if (m.getReqId().equals(reqId)) {
                sendMsg = (DbMyMsg) m;
            }
        }
        final DbMyMsg myMsg = sendMsg;

        mMsgListAdapter.setmDatas(topic.mergedMsgs);
        mMsgListAdapter.notifyDataSetChanged();
        mMsgListRecyclerView.scrollToPosition(mMsgListAdapter.getItemCount() - 1);

        // 数据存储，UI显示都完成后，http发送
        httpQueueHelper.addRequest(saveTextMsgRequest, SaveTextMsgResponse.class, new HttpCallback<SaveTextMsgResponse>() {
            @Override
            public void onSuccess(RequestBase request, SaveTextMsgResponse ret) {
                if (ret.data.topicMsg.size() > 0) {
                    ImMsg imMsg = ret.data.topicMsg.get(0);
                    myMsg.setMsgId(imMsg.msgId); // 由于和mqtt异步，这样能保证更新msgId
                }
                myMsg.setState(DbMyMsg.State.Success.ordinal());
                topic.setShowDot(false);
                //新的更新方法
                DatabaseDealer.updateResendMsg(myMsg, "mqtt");
//                myMsg.save();
                mMsgListAdapter.notifyDataSetChanged();
            }

            @Override
            public void onFail(RequestBase request, Error error) {
                myMsg.setState(DbMyMsg.State.Failed.ordinal());
                //更新数据库的方法
                DatabaseDealer.updateResendMsg(myMsg, "local");
//                myMsg.save();
                mMsgListAdapter.notifyDataSetChanged();
            }
        });

        mMsgEditText.setText("");
    }


    private void doSend(final String msg, final String reqId) {
        final String msgReqId = (reqId == null ? UUID.randomUUID().toString() : reqId);

        // 预先插入mock topic
        if ((memberId > 0) && (topic == null)) {
            // 私聊尚且没有topic，需要创建mock topic
            DbTopic mockTopic = DatabaseDealer.mockTopic();
            DbMember myself = DatabaseDealer.getMemberById(Constants.imId);
            DbMember member = DatabaseDealer.getMemberById(memberId);
            mockTopic.getMembers().add(myself);
            mockTopic.getMembers().add(member);
            if (fromTopicId > 0) { // 来自群聊点击的私聊
                mockTopic.setFromTopic(Long.toString(fromTopicId));
            }
            mockTopic.save();
            topic = mockTopic;
            mMsgListAdapter.setTopic(topic);

            NewTopicCreatedEvent newTopicEvent = new NewTopicCreatedEvent();
            newTopicEvent.dbTopic = mockTopic;
            EventBus.getDefault().post(newTopicEvent);
        }

        // 预先插入mock msg
        //if (DatabaseDealer.isMockTopic(topic)) {
        DbMyMsg myMsg = new DbMyMsg();
        myMsg.setState(DbMyMsg.State.Sending.ordinal());
        myMsg.setReqId(msgReqId);
        myMsg.setMsgId(latestMsgId());
        myMsg.setTopicId(topic.getTopicId());
        myMsg.setSenderId(Constants.imId);
        myMsg.setSendTime(new Date().getTime());
        myMsg.setContentType(10);
        myMsg.setMsg(msg);
        myMsg.setFrom("local");
        //新的更新数据库的方法 如果数据库没有这条数据  内部进行save 操作
        DbMyMsg dbMyMsg = DatabaseDealer.updateResendMsg(myMsg, "local");
//        myMsg.save();
        topic.mergedMsgs.add(0, dbMyMsg);
        mMsgListAdapter.setmDatas(topic.mergedMsgs);
        mMsgListAdapter.notifyDataSetChanged();
        //}

        // 对于是mock topic的需要先创建topic
        if (DatabaseDealer.isMockTopic(topic)) {
            TopicCreateTopicRequest createTopicRequest = new TopicCreateTopicRequest();
            createTopicRequest.imToken = Constants.imToken;
            createTopicRequest.topicType = "1"; // 私聊
            createTopicRequest.imMemberIds = Long.toString(Constants.imId) + "," + Long.toString(memberId);
            createTopicRequest.fromGroupTopicId = topic.getFromTopic();
            createTopicRequest.startRequest(TopicCreateTopicResponse.class, new HttpCallback<TopicCreateTopicResponse>() {
                @Override
                public void onSuccess(RequestBase request, TopicCreateTopicResponse ret) {
                    ImTopic imTopic = null;
                    for (ImTopic topic : ret.data.topic) {
                        imTopic = topic;
                    }
                    // 应该只有一个imTopic

                    // 1，通知移除mock topic
                    DbTopic mockTopic = topic;
                    MockTopicRemovedEvent mockRemoveEvent = new MockTopicRemovedEvent();
                    mockRemoveEvent.dbTopic = mockTopic;
                    EventBus.getDefault().post(mockRemoveEvent);


                    // 2，添加server返回的real topic
                    DbTopic realTopic = DatabaseDealer.updateDbTopicWithImTopic(imTopic);
                    realTopic.latestMsgTime = imTopic.latestMsgTime;
                    realTopic.latestMsgId = imTopic.latestMsgId;
                    realTopic.save();
                    topic = realTopic;

                    // 3，做mock topic 和 real topic间msgs的转换
                    DatabaseDealer.migrateMsgsForMockTopic(mockTopic, realTopic);

                    // 4, 通知新增real topic
                    NewTopicCreatedEvent newTopicEvent = new NewTopicCreatedEvent();
                    newTopicEvent.dbTopic = realTopic;
                    EventBus.getDefault().post(newTopicEvent);

                    mMsgListAdapter.notifyDataSetChanged();
                    doSendMsg(msg, msgReqId);
                }

                @Override
                public void onFail(RequestBase request, Error error) {
                    DatabaseDealer.topicCreateFailed(topic);
                    mMsgListAdapter.notifyDataSetChanged();
                }
            });
        } else {
            // 已经有对话，直接发送即可
            doSendMsg(msg, msgReqId);
        }
    }


    // region handler
    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            mMsgListAdapter.setIsLoading(false);
            ptrHelper.loadingComplete();
        }
    };
    // endregion

    //region callback
    private OnRecyclerViewItemClickCallback<DbMsg> onDbMsgCallback = new OnRecyclerViewItemClickCallback<DbMsg>() {
        @Override
        public void onItemClick(int position, DbMsg dbMsg) {
            if (dbMsg instanceof DbMyMsg) {
                final DbMyMsg myMsg = (DbMyMsg) dbMsg;
                if (myMsg.getState() == DbMyMsg.State.Failed.ordinal()) {
                    // 重新发送
                    topic.mergedMsgs.remove(myMsg);
                    // 1, 先更新数据库中 数据库中 一定存在此条
                    myMsg.setState(DbMyMsg.State.Sending.ordinal());
                    myMsg.setMsgId(latestMsgId());
                    DatabaseDealer.updateResendMsg(myMsg,"local");
//                    myMsg.save();

                    doSend(myMsg.getMsg(), myMsg.getReqId());
                }
            }
        }
    };

    private boolean hasMoreMsgs = true;
    private OnPullToRefreshCallback mOnLoadMoreCallback = new OnPullToRefreshCallback() {
        @Override
        public void onLoadMore() {
            if (hasMoreMsgs) {
                mMsgListAdapter.setIsLoading(true);
                mMsgListAdapter.notifyItemRangeInserted(0, 1);
                // 先从网络取，如果失败了则由数据库重建
                final DbMsg earliestMsg = topic.mergedMsgs.get(topic.mergedMsgs.size() - 1);
                GetTopicMsgsRequest getTopicMsgsRequest = new GetTopicMsgsRequest();
                getTopicMsgsRequest.imToken = Constants.imToken;
                getTopicMsgsRequest.topicId = Long.toString(topic.getTopicId());
                getTopicMsgsRequest.startId = Long.toString(earliestMsg.getMsgId());
                getTopicMsgsRequest.order = "desc";
                getTopicMsgsRequest.startRequest(GetTopicMsgsResponse.class, new HttpCallback<GetTopicMsgsResponse>() {
                    @Override
                    public void onSuccess(RequestBase request, GetTopicMsgsResponse ret) {
                        ptrHelper.loadingComplete();
                        mMsgListAdapter.setIsLoading(false);
                        mMsgListAdapter.notifyItemRangeRemoved(0, 1);

                        final DbMsg theRefreshingMsg = topic.mergedMsgs.get(topic.mergedMsgs.size() - 1);

                        if (ret.data.topicMsg.size() < DatabaseDealer.pagesize) {
                            hasMoreMsgs = false;
                        }

                        if (ret.data.topicMsg.size() > 0) {
                            // 去除最后一条重复的
                            ret.data.topicMsg.remove(0);
                        }

                        for (ImMsg msg : ret.data.topicMsg) {
                            DbMsg dbMsg = DatabaseDealer.updateDbMsgWithImMsg(msg, "http", Constants.imId);
                            topic.mergedMsgs.add(dbMsg);

                            if (dbMsg.getMsgId() > topic.latestMsgId) {
                                topic.latestMsgId = dbMsg.getMsgId();
                            }
                        }

                        mMsgListAdapter.setmDatas(topic.mergedMsgs);
                        int num = mMsgListAdapter.uiAddedNumberForMsg(theRefreshingMsg);
                        if (num > 0) {
                            mMsgListAdapter.notifyItemRangeRemoved(0, 1); // 最后的Datetime需要去掉
                            mMsgListAdapter.notifyItemRangeInserted(0, num);
                        }
                    }

                    @Override
                    public void onFail(RequestBase request, Error error) {
                        // 从数据库获取
                        ptrHelper.loadingComplete();
                        mMsgListAdapter.setIsLoading(false);
                        mMsgListAdapter.notifyItemRangeRemoved(0, 1);

                        final DbMsg theRefreshingMsg = topic.mergedMsgs.get(topic.mergedMsgs.size() - 1);

                        List<DbMsg> msgs = DatabaseDealer.getTopicMsgs(topic.getTopicId(),
                                earliestMsg.getMsgId(),
                                DatabaseDealer.pagesize);

                        // 从数据库取回的消息，包含了startIndex这一条，而对于未发送成功的MyMsg则可能有多条
                        for(Iterator<DbMsg> i = msgs.iterator(); i.hasNext();) {
                            DbMsg uiMsg = i.next();
                            if (uiMsg.getMsgId() == earliestMsg.getMsgId()) {
                                i.remove();
                            }
                        }

                        if (msgs.size() < DatabaseDealer.pagesize) {
                            hasMoreMsgs = false;
                        }
                        topic.mergedMsgs.addAll(msgs);
                        mMsgListAdapter.setmDatas(topic.mergedMsgs);
                        int num = mMsgListAdapter.uiAddedNumberForMsg(theRefreshingMsg);
                        if (num > 0) {
                            mMsgListAdapter.notifyItemRangeRemoved(0, 1); // 最后的Datetime需要去掉
                            mMsgListAdapter.notifyItemRangeInserted(0, num);
                        }
                    }
                });
            }

        }
    };
    //endregion

    //region mqtt
    @Subscribe
    public void onMqttMsg(MqttProtobufDealer.NewMsgEvent event) {
        ImMsg msg = event.msg;
        DbMsg dbMsg = DatabaseDealer.updateDbMsgWithImMsg(msg, "mqtt", Constants.imId);
        if (msg.topicId != topic.getTopicId()) {
            // 不是本topic的直接抛弃
            return;
        }

        //topic.mergedMsgs.add(0, dbMsg);
        DatabaseDealer.pendingMsgToTopic(dbMsg, topic);
        if (dbMsg.getMsgId() > topic.latestMsgId) {
            topic.latestMsgId = dbMsg.getMsgId();
            topic.latestMsgTime = dbMsg.getSendTime();
        }
        //在对话内收到消息 默认取消红点的显示  bug1307
        topic.setShowDot(false);
        topic.save();

        mMsgListAdapter.setmDatas(topic.mergedMsgs);
        mMsgListAdapter.notifyDataSetChanged();
        mMsgListRecyclerView.scrollToPosition(mMsgListAdapter.getItemCount() - 1);
    }
    //endregion

    //region util
    private long latestMsgId() {
        long latestMsgId = -1;
        for (DbMsg dbMsg : topic.mergedMsgs) {
            if (dbMsg.getMsgId() > latestMsgId) {
                latestMsgId = dbMsg.getMsgId();
            }
        }
        return latestMsgId;
    }
    //endregion


    /*-------------------------------  发送图片逻辑     ------------------------------------*/
    private ImagePicker imagePicker;

    private void initImagePicker() {
        GlideImageLoader glideImageLoader = new GlideImageLoader();
        imagePicker = ImagePicker.getInstance();
        imagePicker.setImageLoader(glideImageLoader);
        //显示拍照按钮
        imagePicker.setShowCamera(true);
        //允许裁剪（单选才有效）
        imagePicker.setCrop(false);
        //选中数量限制
        imagePicker.setSelectLimit(9);
        //裁剪框的形状
    }

    private ChoosePicsDialog mClassCircleDialog;

    private void showChoosePicsDialog() {
        if (mClassCircleDialog == null) {
            mClassCircleDialog = new ChoosePicsDialog(ImMsgListActivity.this);
            mClassCircleDialog.setClickListener(new ChoosePicsDialog.OnViewClickListener() {
                @Override
                public void onAlbumClick() {
                    ImMsgListActivity.requestWriteAndReadPermission(new OnPermissionCallback() {
                        @Override
                        public void onPermissionsGranted(@Nullable List<String> deniedPermissions) {
                            Intent intent = new Intent(ImMsgListActivity.this, ImageGridActivity.class);
                            startActivityForResult(intent, IMAGE_PICKER);
                        }

                        @Override
                        public void onPermissionsDenied(@Nullable List<String> deniedPermissions) {
                            Toast.makeText(ImMsgListActivity.this, R.string.no_storage_permissions, Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onCameraClick() {
                    ImMsgListActivity.requestCameraPermission(new OnPermissionCallback() {
                        @Override
                        public void onPermissionsGranted(@Nullable List<String> deniedPermissions) {

                            Intent intent = new Intent(ImMsgListActivity.this, ImageGridActivity.class);
                            // 是否是直接打开相机
                            intent.putExtra(ImageGridActivity.EXTRAS_TAKE_PICKERS, true);
                            startActivityForResult(intent, REQUEST_CODE_SELECT);
                        }

                        @Override
                        public void onPermissionsDenied(@Nullable List<String> deniedPermissions) {
                            Toast.makeText(ImMsgListActivity.this, R.string.no_storage_permissions, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        }
        mClassCircleDialog.show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case IMAGE_PICKER:
            case REQUEST_CODE_SELECT:

                getQiNiuToken(createSelectedImagesList(data));

//                uploadPicsByQiNiu(images);
                break;
            default:
                break;
        }

    }


    /**
     * 先在列表中显示压缩后图片
     *
     * @param imgs 图片数据
     */
    private void showReSizedPics(List<String> imgs) {

    }

    /**
     * 构造需要的图片数据
     *
     * @param data
     */
    private ArrayList<ImageItem> createSelectedImagesList(Intent data) {
        ArrayList<ImageItem> images = null;
        try {
            images = (ArrayList<ImageItem>) data.getSerializableExtra(ImagePicker.EXTRA_RESULT_ITEMS);
        } catch (Exception e) {

        }
        if (images == null) {
            return null;
        }

        return images;

    }

    /**
     * 使用鲁班压缩图片至200kb左右
     *
     * @param imageItemArrayList
     * @return
     */
    private List<String> reSizePics(List<ImageItem> imageItemArrayList) {
        List<String> imagePathList = new ArrayList<>();
        final List<String> imageReSizedPathList = new ArrayList<>();
        for (ImageItem imageItem : imageItemArrayList) {
            imagePathList.add(imageItem.path);
        }
        Luban.with(ImMsgListActivity.this)
                .load(imagePathList)
                .ignoreBy(200)
                .setCompressListener(new OnCompressListener() {
                    @Override
                    public void onStart() {
                    }

                    @Override
                    public void onSuccess(File file) {
                        imageReSizedPathList.add(file.getAbsolutePath());
                        uploadPicByQiNiu(file.getAbsolutePath());

                    }

                    @Override
                    public void onError(Throwable e) {
                    }
                }).launch();

        return imageReSizedPathList;
    }

    UUID mGetQiNiuTokenUUID;

    /**
     * 获取七牛token
     *
     * @param imageItemArrayList
     */
    private void getQiNiuToken(final ArrayList<ImageItem> imageItemArrayList) {
        GetQiNiuTokenRequest getQiNiuTokenRequest = new GetQiNiuTokenRequest();
        getQiNiuTokenRequest.from = "100";
        getQiNiuTokenRequest.dtype = "app";
        getQiNiuTokenRequest.token = Constants.token;
        mGetQiNiuTokenUUID = getQiNiuTokenRequest.startRequest(GetQiNiuTokenResponse.class, new HttpCallback<GetQiNiuTokenResponse>() {
            @Override
            public void onSuccess(RequestBase request, GetQiNiuTokenResponse ret) {
                mGetQiNiuTokenUUID = null;
                mCancelQiNiuUploadPics = false;
                if (ret != null) {
                    if (ret.code == 0) {
                        mQiniuToken = ret.getData().getToken();
                        reSizePics(imageItemArrayList);

                    } else {
//                        rootView.hiddenLoadingView();
//                        ToastUtil.showToast(getApplicationContext(), ret.getError() != null ? ret.getError().getMessage() : getString(R.string.get_qiniu_token_error));
                    }

                } else {
//                    rootView.hiddenLoadingView();
//                    ToastUtil.showToast(getApplicationContext(), getString(R.string.get_qiniu_token_error));
                }
            }

            @Override
            public void onFail(RequestBase request, Error error) {
                mGetQiNiuTokenUUID = null;
//                rootView.hiddenLoadingView();
//                ToastUtil.showToast(getApplicationContext(), error.getMessage());
            }
        });
    }

    private UploadManager uploadManager = null;
    /**
     * 此参数设置为true时 则正在执行的七牛上传图片将被停止
     */
    private boolean mCancelQiNiuUploadPics = false;
    private Configuration config = new Configuration.Builder()
            // 分片上传时，每片的大小。 默认256K
            .chunkSize(2 * 1024 * 1024)
            // 启用分片上传阀值。默认512K
            .putThreshhold(4 * 1024 * 1024)
            // 链接超时。默认10秒
            .connectTimeout(10)
            // 服务器响应超时。默认60秒
            .responseTimeout(60)
            .build();

    private void uploadPicByQiNiu(final String picPath) {
        if (uploadManager == null) {
            uploadManager = new UploadManager(config);
        }
        uploadManager.put(picPath, null, mQiniuToken, new UpCompletionHandler() {
            @Override
            public void complete(String s, ResponseInfo responseInfo, JSONObject jsonObject) {
                try {
                    Integer[] widthAndHeight = getPicWithAndHeight(picPath);
                    doSendImgMsg(picPath, jsonObject.getString("key"), widthAndHeight[0], widthAndHeight[1]);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, new UploadOptions(null, null, false, new UpProgressHandler() {
            @Override
            public void progress(String s, double v) {
            }
        }, new UpCancellationSignal() {
            @Override
            public boolean isCancelled() {
                return mCancelQiNiuUploadPics;
            }
        }));
    }

    /**
     * 计算图片的宽高
     *
     * @param imgPath 图片路径
     * @return Integer【】 第一个参数表示width 第二个参数表示height
     */
    private Integer[] getPicWithAndHeight(String imgPath) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        Bitmap bitmap = BitmapFactory.decodeFile(imgPath, options);
        return new Integer[]{options.outWidth, options.outHeight};
    }


    private void doSendImgMsg(final String imagePath, final String rid, final int with, final int height) {
        if ((memberId > 0) && (topic == null)) {
            // 是新建的Topic，需要先create topic
            TopicCreateTopicRequest createTopicRequest = new TopicCreateTopicRequest();
            createTopicRequest.imToken = Constants.imToken;
            createTopicRequest.topicType = "1"; // 私聊
            createTopicRequest.imMemberIds = Long.toString(Constants.imId) + Long.toString(memberId);
            createTopicRequest.startRequest(TopicCreateTopicResponse.class, new HttpCallback<TopicCreateTopicResponse>() {
                @Override
                public void onSuccess(RequestBase request, TopicCreateTopicResponse ret) {
                    for (ImTopic imTopic : ret.data.topic) {
                        DbTopic dbTopic = DatabaseDealer.updateDbTopicWithImTopic(imTopic);
                        dbTopic.latestMsgTime = imTopic.latestMsgTime;
                        dbTopic.latestMsgId = imTopic.latestMsgId;
                        dbTopic.save();
                        topic = dbTopic;

                        NewTopicCreatedEvent event = new NewTopicCreatedEvent();
                        event.dbTopic = dbTopic;
                        EventBus.getDefault().post(event);

                        doSendImage(imagePath, rid, with, height);
                    }
                }

                @Override
                public void onFail(RequestBase request, Error error) {
                    // TBD:cailei 这里需要弹个toast？
                }
            });
        } else {
            // 已经有对话，直接发送即可
            doSendImage(imagePath, rid, with, height);

        }
    }

    private void doTakePic() {

    }


    /**
     * 发送图片
     */
    private void doSendImage(String imagePath, String rid, int width, int height) {
        if (TextUtils.isEmpty(rid)) {
            return;
        }

        SaveImageMsgRequest saveImageMsgRequest = new SaveImageMsgRequest();
        saveImageMsgRequest.imToken = Constants.imToken;
        saveImageMsgRequest.topicId = Long.toString(topic.getTopicId());
        saveImageMsgRequest.rid = rid;
        saveImageMsgRequest.height = String.valueOf(height);
        saveImageMsgRequest.width = String.valueOf(width);


        final DbMyMsg myMsg = new DbMyMsg();
        myMsg.setState(DbMyMsg.State.Sending.ordinal());
        myMsg.setReqId(saveImageMsgRequest.reqId);
        myMsg.setMsgId(latestMsgId());
        myMsg.setTopicId(topic.getTopicId());
        myMsg.setSenderId(Constants.imId);
        myMsg.setSendTime(new Date().getTime());
        //type==20 为图片
        myMsg.setContentType(20);
        myMsg.setFrom("local");
        myMsg.setViewUrl(imagePath);
        myMsg.setHeight(height);
        myMsg.setWith(width);
        myMsg.save();
        topic.mergedMsgs.add(0, myMsg);
        mMsgListAdapter.setmDatas(topic.mergedMsgs);
        mMsgListAdapter.notifyDataSetChanged();
        mMsgListRecyclerView.scrollToPosition(mMsgListAdapter.getItemCount() - 1);

        // 数据存储，UI显示都完成后，http发送
        httpQueueHelper.addRequest(saveImageMsgRequest, SaveImageMsgResponse.class, new HttpCallback<SaveImageMsgResponse>() {
            @Override
            public void onSuccess(RequestBase request, SaveImageMsgResponse ret) {
                myMsg.setState(DbMyMsg.State.Success.ordinal());
                myMsg.save();
                mMsgListAdapter.notifyDataSetChanged();
            }

            @Override
            public void onFail(RequestBase request, Error error) {
                myMsg.setState(DbMyMsg.State.Failed.ordinal());
                myMsg.save();
                mMsgListAdapter.notifyDataSetChanged();
            }
        });

        mMsgEditText.setText("");
    }
}
