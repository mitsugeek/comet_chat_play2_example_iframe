package controllers;

import play.*;
import play.mvc.*;
import play.libs.*;
import play.libs.F.*;

import akka.util.*;
import akka.actor.*;

import java.util.*;
import java.text.*;
import scala.concurrent.duration.Duration;

import static java.util.concurrent.TimeUnit.*;

import scala.concurrent.ExecutionContext$;

import org.codehaus.jackson.JsonNode;

import views.html.*;

public class Application extends Controller {

    //Comet用のアクター
    final static ActorRef clock = Clock.instance;

    /**
     * メインページへのアクセス
     */
    public static Result index() {
        return ok(index.render());
    }
    
    /**
     * チャットルーム入室処理
     * アクターにCometの追加を行う
     */
    public static Result liveClock(String _uuid, String _name) {
        final String uuid = _uuid;
        final String name = _name;
        return ok(new Comet("parent.clockChanged") { 
            @Override
            public void onConnected() {
                Logger.info("Comet.onConnected");
                Map<String, Comet> data = new HashMap<String,Comet>();
                data.put(uuid + ":" + name ,this);
                clock.tell(data); 
            } 
        });
    }

    /**
     * メッセージ送信処理
     * アクターにメッセージを通知する
     */
    public static Result tell(){
        Map<String, String[]> requestBody = request().body().asFormUrlEncoded();
        String message = requestBody.containsKey("text") ? requestBody.get("text")[0] : "";
        String uuid = requestBody.containsKey("uuid") ? requestBody.get("uuid")[0] : "";
        String uname = requestBody.containsKey("uname") ? requestBody.get("uname")[0] : "";

        Map<String, String> response = new HashMap<String,String>();


        response.put("message",message);

        Date date = new Date();
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        response.put("datetime",df.format(date));
        response.put("uuid",uuid);
        response.put("uname",uname);
        Clock.instance.tell(Json.toJson(response));
        return ok("");
    }
 
    /**
     * チャットルームの通知を制御するアクター
     */
    public static class Clock extends UntypedActor {
        static ActorRef instance = Akka.system().actorOf(new Props(Clock.class));
        public HashMap<Comet,String> sockets = new HashMap<Comet,String>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        

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
         * メッセージ受信
         */
        public void onReceive(Object message) {

            // Handle connections
            if(message instanceof Map<?,?>) {
                //COMET新規作成時：チャットルームに入った時。
                Logger.info("onReceive");
                Map<String, Comet> data = (Map<String, Comet>)message;
                String stringObj = "";
                Comet cometObj = null;
                for(Map.Entry<String, Comet> e : data.entrySet()) {
                    stringObj = e.getKey();
                    cometObj = e.getValue();
               }
               if(cometObj instanceof Comet) {

                    final Comet cometSocket = (Comet)cometObj;
                    
                    if(sockets.containsKey(cometSocket)) {
                    } else {
                        
                        // Register disconnected callback 
                        cometSocket.onDisconnected(new Callback0() {
                            public void invoke() {
                                getContext().self().tell(cometSocket);
                            }
                        });
                        
                        // New browser connected
                        sockets.put(cometSocket, stringObj);
                        Logger.info(stringObj);
                        Logger.info("New browser connected (" + sockets.size() + " browsers currently connected)");
                        
                        Map<String, String> response = new HashMap<String,String>();
                        String uuid = stringObj.substring(0,stringObj.indexOf(":"));
                        String uname = stringObj.substring(stringObj.indexOf(":") + 1 , stringObj.length());
                        response.put("message",uname + "さんが入室しました。");
                        response.put("datetime",dateFormat.format(new Date()));
                        response.put("uuid",uuid);
                        response.put("uname","system");

                        for( Map.Entry<Comet,String> ck : sockets.entrySet()) {
                            ck.getKey().sendMessage(Json.toJson(response));
                        }
                    }
               }
            }
            if(message instanceof Comet) {
                //Commet送信時に通信できない場合は退室とする
                Logger.info(message.toString());
                Comet cometSocket = (Comet)message;
                if(sockets.containsKey(cometSocket)) {
                    String stringObj = sockets.get(cometSocket);

                    // Brower is disconnected
                    sockets.remove((Comet)message);
                    Logger.info("Browser disconnected (" + sockets.size() + " browsers currently connected)");

                    //message
                    Map<String, String> response = new HashMap<String,String>();
                    String uuid = stringObj.substring(0,stringObj.indexOf(":"));
                    String uname = stringObj.substring(stringObj.indexOf(":") + 1 , stringObj.length());
                    response.put("message", uname + "さんが退室しました。");
                    response.put("datetime",dateFormat.format(new Date()));
                    response.put("uuid",uuid);
                    response.put("uname","system");

                    for( Map.Entry<Comet,String> ck : sockets.entrySet()) {
                        ck.getKey().sendMessage(Json.toJson(response));
                    }

                }
            }
            
            // メッセージ送信時はJsonNodeで受け取る
            if(message instanceof JsonNode){
                for( Map.Entry<Comet,String> ck : sockets.entrySet()) {
                    ck.getKey().sendMessage((JsonNode)message);
                }
            }

            //チェック要の通知処理空文字を定期的に送付し通信が行われているか確認する
            if(message instanceof String) {
                Logger.info((String)message);
                if("CHECK".equals(message)) {
                    for( Map.Entry<Comet,String> ck : sockets.entrySet()) {
                        ck.getKey().sendMessage((String)"");
                    }
                }                
            }
        }
    }
  
}
