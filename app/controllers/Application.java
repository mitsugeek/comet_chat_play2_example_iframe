package controllers;

import play.*;
import play.mvc.*;
import play.libs.*;
import play.libs.F.*;
import akka.actor.*;
import java.util.*;
import java.text.*;
import scala.concurrent.duration.Duration;
import static java.util.concurrent.TimeUnit.*;
import org.codehaus.jackson.JsonNode;
import play.mvc.Http.Request;
import views.html.*;

public class Application extends Controller {

	/**
	 * comet用のアクター
	 */
    final static ActorRef chatRoomInstance = ChatRoom.instance;
    final static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * メインページへのアクセス
     */
    public static Result index() {

        //セッション管理
        String uuid=session("uuid");
        if(uuid==null) {
        	//セッション開始
            uuid=java.util.UUID.randomUUID().toString();
            session("uuid", uuid);
        }

        //テンプレートをレンダリング
        return ok(index.render());
    }
    
    /**
     * チャットルーム入室処理
     * アクターにCometの追加を行う
     */
    public static Result inroomchat(String _name) {
        String _uuid=session("uuid");
        if(_uuid==null) {
            //return badRequest("no session...");
        	//負荷テストの為、COMET作成にUUIDを振る
            _uuid=java.util.UUID.randomUUID().toString();
        }

        //in room logger
        in_room_logger();

        final String name = _name;
        final String uuid = _uuid;
        return ok(new Comet("parent.chatmsg") { 
            @Override
            public void onConnected() {
                chatRoomInstance.tell( new InRoom(this, new User(uuid,name)), null);
            }
            
        });
    }
    
    private static void in_room_logger(){
        Request req = play.mvc.Http.Context.current().request();
        String remote_address = req.getHeader("x-forwarded-for") ;
        String remoteaddress = request().remoteAddress() ;
        String user_agent = request().getHeader("User-Agent");
        Logger.info("[in room:" + dateFormat.format(new Date()) + "],ip:"+remoteaddress+",fip:"+remote_address + ",ua:" + user_agent);
    }

    /**
     * メッセージ送信処理
     * アクターにメッセージを通知する
     */
    public static Result tell(){
        String uuid=session("uuid");
        if(uuid==null) {
            return badRequest("no session...");
        }
        Map<String, String[]> requestBody = request().body().asFormUrlEncoded();
        String message = requestBody.containsKey("text") ? requestBody.get("text")[0] : "";
        String uname = requestBody.containsKey("uname") ? requestBody.get("uname")[0] : "";
        RoomMessage msg = new RoomMessage(uuid,uname,message);
        chatRoomInstance.tell(msg,null);
        return ok("");
    }
 
    /**
     * チャットルームに入室するユーザの情報 
     */
    public static class User {
    	public String uuid;
    	public String name;
    	public User(String uuid, String name){
    		this.uuid = uuid;
    		this.name = name;
    	}
    }
    
    /**
     * チャットルームに入室する際にアクターへ渡すメッセージ
     */
    public static class InRoom {
    	public Comet comet;
    	public User user;
    	public InRoom(Comet comet,User user){
    		this.comet = comet;
    		this.user = user;
    	}
    }
    
    /**
     * チャットルームに投げるメッセージ
     */
    public static class RoomMessage{
    	public String message;
    	public String datetime;
    	public String uuid;
    	public String uname;
    	public String sankasu;
    	public RoomMessage(String uuid, String uname, String message){
    		this.uuid = uuid;
    		this.uname = org.apache.commons.lang3.StringEscapeUtils.escapeHtml4(uname);
    		this.message = org.apache.commons.lang3.StringEscapeUtils.escapeHtml4(message);
    	}
    }
    
    /**
     * チャットルームの通知を制御するアクター
     */
    public static class ChatRoom extends UntypedActor {
    	/**
    	 * singleton
    	 */
        final static ActorRef instance = Akka.system().actorOf(new Props(ChatRoom.class));

        /**
         * ソケットの情報
         */
        final static public HashMap<Comet,User> sockets = new HashMap<Comet,User>();
        
        /**
         * 日付のフォーマットに利用
         */
        final static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        

        /**
         * スケジュール処理（定期処理）
         * 一定間隔でPUSHを行う
         */
        static {
            //定期的にComet経由の通知を行う
            Akka.system().scheduler().schedule(
                Duration.Zero(),
                Duration.create(5000, MILLISECONDS),
                instance, "CHECK",  Akka.system().dispatcher()
            );
        }

        /**
         * Cometでの接続一覧に対してメッセージを送信する
         */
        public void sentMessage(RoomMessage msgObj){
        	msgObj.datetime = dateFormat.format(new Date());
        	msgObj.sankasu = Integer.toString(sockets.size());
        	JsonNode message = Json.toJson(msgObj);
            for( Map.Entry<Comet,User> ck : sockets.entrySet()) {
                ck.getKey().sendMessage(message);
            }
        }
        
        /**
         * メッセージ受信
         */
        public void onReceive(Object message) {

        	if(message instanceof InRoom) {
                //COMET新規作成時：チャットルームに入った時。
            	InRoom data = (InRoom)message;
                User user = data.user;
                Comet cometObj = data.comet;
               if(cometObj instanceof Comet) {

                    final Comet cometSocket = (Comet)cometObj;
 
                    // Register disconnected callback 
                    cometSocket.onDisconnected(new Callback0() {
                    	//通信が途絶えた場合の処理(退室処理)
                        public void invoke() {
                        	if(sockets.containsKey(cometSocket)) {
                        		User user = sockets.get(cometSocket);
                                // Browser is disconnected
                                sockets.remove(cometSocket);
                                Logger.info("Browser disconnected (" + sockets.size() + " browsers currently connected)");
                                RoomMessage msg = new RoomMessage(user.uuid,"system",user.name + "さんが退室しました。");
                                chatRoomInstance.tell(msg,null);
                        	}
                        }
                    });
                    
                    // 入室
                    sockets.put(cometSocket, user);
                    Logger.info("New browser connected (" + sockets.size() + " browsers currently connected)");
                    RoomMessage msg = new RoomMessage(user.uuid,"system",user.name + "さんが入室しました。");
                    chatRoomInstance.tell(msg,null);
               }

        	}else if(message instanceof RoomMessage){
            	//チャット向けのメッセージを受信した場合
            	this.sentMessage((RoomMessage) message);
 
            }else if(message instanceof String){
            	//チェック要の通知処理空文字を定期的に送付し通信が行われているか確認する
                if("CHECK".equals(message)) {
                    for( Map.Entry<Comet,User> ck : sockets.entrySet()) {
                        ck.getKey().sendMessage((String)"");
                    }
                }             	
            }
        }
    }
  
}
