package com.example.splitease.firebase;

import androidx.test.core.app.ApplicationProvider;
import com.example.splitease.auth.*;
import com.example.splitease.debug.FirebaseAuthSandbox;
import com.example.splitease.model.User;
import com.example.splitease.repository.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Only the localhost demo Auth emulator. Never sends owner credentials or test data to production. */
final class AuthEmulatorSupport {
    static final String PROJECT="demo-splitease-auth",EMAIL="adapter-test@example.test",PASSWORD="Strong1!",CHANGED="Changed2!";
    interface Call<T>{void run(RepositoryCallback<T> cb);}
    static <T>T await(Call<T> call)throws Exception {CompletableFuture<T> result=new CompletableFuture<>();call.run(new RepositoryCallback<T>(){public void onSuccess(T value){result.complete(value);}public void onError(Exception error){result.completeExceptionally(error);}});return result.get(40,TimeUnit.SECONDS);}
    static SessionState state(AuthService auth,SessionState.Status status)throws Exception {java.util.List<String> history=new java.util.concurrent.CopyOnWriteArrayList<>();CompletableFuture<SessionState> result=new CompletableFuture<>();ObservableQuery.Subscription subscription=auth.sessions().subscribe(Runnable::run,new ObservableQuery.Observer<SessionState>(){public void onChanged(SessionState value){history.add(value.status.name());if(value.status==status)result.complete(value);}public void onError(Exception error){result.completeExceptionally(error);}});try{return result.get(40,TimeUnit.SECONDS);}catch(TimeoutException timeout){throw new AssertionError("Expected "+status+"; observed "+history);}finally{subscription.close();}}
    static void error(AuthException.Code expected,Call<?> call)throws Exception {try{await(call);fail("Expected auth error");}catch(ExecutionException failure){assertTrue(failure.getCause() instanceof AuthException);assertEquals(expected,((AuthException)failure.getCause()).code);}}
    static void requireEmulator()throws Exception {assertEquals(PROJECT,FirebaseAuthSandbox.sdk(ApplicationProvider.getApplicationContext()).getApp().getOptions().getProjectId());request("GET","/emulator/v1/projects/"+PROJECT+"/config",null);}
    static JSONObject request(String method,String path,JSONObject body)throws Exception {
        if(!path.startsWith("/emulator/v1/projects/"+PROJECT)&&!path.startsWith("/identitytoolkit.googleapis.com/"))throw new IllegalArgumentException("Demo emulator API only");
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:9099"+path).openConnection();connection.setRequestMethod(method);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer owner");
        try{if(body!=null){connection.setDoOutput(true);try(OutputStream out=connection.getOutputStream()){out.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}int status=connection.getResponseCode();assertTrue("Emulator HTTP "+status,status>=200&&status<300);try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);return new JSONObject(output.toString("UTF-8"));}}finally{connection.disconnect();}
    }
    static void finishReset(String email,String password)throws Exception {
        JSONArray codes=request("GET","/emulator/v1/projects/"+PROJECT+"/oobCodes",null).getJSONArray("oobCodes");String code=null;
        for(int i=0;i<codes.length();i++){JSONObject value=codes.getJSONObject(i);if(email.equals(value.optString("email"))&&"PASSWORD_RESET".equals(value.optString("requestType")))code=value.getString("oobCode");}
        assertNotNull("Reset request exists in local emulator",code);
        request("POST","/identitytoolkit.googleapis.com/v1/accounts:resetPassword?key=demo-api-key",new JSONObject().put("oobCode",code).put("newPassword",password));
    }
}
