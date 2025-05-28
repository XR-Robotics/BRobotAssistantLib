package com.picovr.robotassistantlib;

import android.util.Log;

import com.unity3d.player.UnityPlayer;

public class UnitySender {

    private static final String gameObjectName = "AndroidProxy";

    private static final String functionName = "AndroidCall";

    public static void Send(String type,String content)
    {
        String msg=type+"|"+content;
       // Log.i("UnitySender","Send "+msg);
        UnityPlayer.UnitySendMessage(gameObjectName, functionName, msg);
    }

    public static void RequestPermissionsBack(int res)
    {
        Send("RequestPermissionsBack", String.valueOf(res));
    }
}
