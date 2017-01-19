package it.unisannio.security.DoApp.services;

import android.app.IntentService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import it.unisannio.security.DoApp.activities.CounterActivity;
import it.unisannio.security.DoApp.util.PackageInfoExtractor;
import it.unisannio.security.DoApp.util.ReportWriter;
import it.unisannio.security.DoApp.activities.EndActivity;
import it.unisannio.security.DoApp.generators.MalIntentGenerator;
import it.unisannio.security.DoApp.model.Commons;
import it.unisannio.security.DoApp.model.ExceptionReport;
import it.unisannio.security.DoApp.model.LogCatMessage;
import it.unisannio.security.DoApp.model.MalIntent;
import it.unisannio.security.DoApp.parser.LogCatMessageParser;
import it.unisannio.security.DoApp.parser.MessagesFilter;
import it.unisannio.security.DoApp.model.IntentDataInfo;
import com.jaredrummler.apkparser.model.AndroidComponent;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class FuzzerService extends IntentService {

    private String pathFile;
    public FuzzerService() {
        super("FuzzerService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {

            //estraggo il nome dell'app su cui fare fuzzing
            String pkgname = intent.getStringExtra(Commons.pkgName);
            if(pkgname!=null && !pkgname.isEmpty()) {

                Log.i("DoAppLOG", "Start fuzzing to "+pkgname);

                //pulisco il logcat
                killAll("logcat");
                clearLogCat();


                fuzz(pkgname);


                SystemClock.sleep(100);

                //avvio l'activity finale

                Intent end = new Intent(this, EndActivity.class);
                end.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                end.putExtra(Commons.pathFile, pathFile);

                startActivity(end);

            }
        }
    }

    private void fuzz(String pkgname){

        //lista contenente i risultati
        List<ExceptionReport> results = new ArrayList<ExceptionReport>();

        Log.i("DoAppLOG", "Analizzo il manifest...");

        //recupero la lista dei datatype degli IntentFilter esportati dall'app
        PackageInfoExtractor extractor = new PackageInfoExtractor(this);
        List<IntentDataInfo> datas = extractor.extractIntentFiltersDataType(pkgname);


        Log.i("DoAppLOG", "Creo i MalIntent...");

        //ottengo la lista degli intent malevoli
        List<MalIntent> malIntents = MalIntentGenerator.createFromIntentData(datas);

        Log.i("DoAppLOG", "Creati " + malIntents.size()+" MalIntent:");

        //log per conoscere i malintent creati
        int counter = 1;
        for(MalIntent m : malIntents){
            Log.i("DoAppLOG", "\t "+ (counter++) + ". "+m.toString());
        }


        //usato come spareggio se il PID dell'app è uguale ad una Exception già analizzata
        Date lastTime = null;

        //invio uno alla volta i malintent
        int num=1;
        for(MalIntent malIntent : malIntents){

            Log.i("DoAppLOG", "Invio MalIntent n."+ num);
            Log.i("DoAppLOG", "\t"+ malIntent.toString());

            //invio l'intent malevolo
            try{
                switch (malIntent.getTargetComponent().type) {
                    case AndroidComponent.TYPE_ACTIVITY:
                        malIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(malIntent);
                        break;
                    case AndroidComponent.TYPE_BROADCAST_RECEIVER:
                        sendBroadcast(malIntent);
                        break;
                    case AndroidComponent.TYPE_SERVICE:
                        startService(malIntent);
                        break;
                    case AndroidComponent.TYPE_CONTENT_PROVIDER:
                        //BAH
                        break;
                }
            }
            catch(ActivityNotFoundException e){
                e.printStackTrace();
            }
            catch (SecurityException se){
                se.printStackTrace();
            }


            //devo attendere perchè Android è lento come la morte

            SystemClock.sleep(1000);

            //analizzo il logcat alla ricerca di un crash
            Log.i("DoAppLOG", "Analizzo LogCat...");
            List<String> lines = readLogCat();

            LogCatMessageParser parser = new LogCatMessageParser();

            //recupero una lista di LogCatMessage
            List<LogCatMessage> messages = parser.processLogLines(lines);

            //analizzo i messaggi alla ricerca di FATAL EXCEPTION
            List<ExceptionReport> reports = MessagesFilter.filterByFatalException(messages);

            SystemClock.sleep(1000);

            int appPid = getAppPID(pkgname);
            Log.i("DoAppLOG", "PID dell'app: "+String.valueOf(appPid));

            for(ExceptionReport ex : reports){

                if((ex.getAppName().contains(pkgname) || ex.getProcessName().equalsIgnoreCase(pkgname)) && (ex.getPID() == appPid)){
                    if(lastTime==null || (ex.getTime().after(lastTime))) {

                        ex.setMalIntent(malIntent);
                        results.add(ex);

                        Log.i("DoAppLOG", "Trovato crash:");
                        Log.i("DoAppLOG", "\t" + ex.toString());

                        lastTime = ex.getTime();
                    }

                }
            }

            //l'app viene killata in qualsiasi caso per rendere il test stateless
            Log.i("DoAppLOG", "Kill app");
            killAll(pkgname);

            SystemClock.sleep(200);

            Intent intermediate = new Intent(this, CounterActivity.class);
            intermediate.putExtra("msg", "Inviato n." + num + " Intent su "+malIntents.size());
            intermediate.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);
            startActivity(intermediate);

            SystemClock.sleep(1000);
            num++;
        }

        //TODO: effettuare il triage sulla lista di ExceptionReport

        if(results.size()>0)
            pathFile = ReportWriter.scriviSuFile(results, pkgname);


        Log.i("DoAppLOG", "Fuzzing Completato!");
    }

    private void clearLogCat(){
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c","logcat -c"});
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private List<String> readLogCat(){
        BufferedReader bufferedReader;
        List<String> lines = new ArrayList<String>();

        String[] commands = {"su", "-c","logcat -d -v long"};
        java.lang.Process process;
        try {
            process = Runtime.getRuntime().exec(commands);
            bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return lines;

    }


    private List<String> readLogCat(java.lang.Process suProcess){
        BufferedReader bufferedReader;
        List<String> lines = new ArrayList<String>();

        try {
            DataOutputStream os = new DataOutputStream(suProcess.getOutputStream());
            os.writeBytes("logcat -d -v long \n");
            os.flush();
            bufferedReader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return lines;

    }

    private void killApp(int PID){
        java.lang.Process suProcess = null;
        String[] commands = {"su", "-c","kill "+PID};
        try {
            suProcess = Runtime.getRuntime().exec(commands);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int getAppPID(String pkgname) {
        BufferedReader bufferedReader;
        String[] commands = {"su", "-c","pidof "+pkgname};
        java.lang.Process process;
        String line = null;
        try {
            process = Runtime.getRuntime().exec(commands);
            bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            line = bufferedReader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(line!=null)
            return Integer.parseInt(line);
        else
            return -1;
    }

    private void killAll(String processName){
        java.lang.Process suProcess = null;
        String[] commands = {"su", "-c","killall -9 " + processName};
        try {
            suProcess = Runtime.getRuntime().exec(commands);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}