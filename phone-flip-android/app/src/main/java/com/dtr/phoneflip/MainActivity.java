package com.dtr.phoneflip;

import android.app.Activity;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Random;

public class MainActivity extends Activity {
    private SharedPreferences save;
    private int cash, level, xp, day, selected;
    private ArrayList<Phone> stock = new ArrayList<>();
    private LinearLayout root, content, nav;
    private final Random rng = new Random();
    private static final String[] NAMES = {"Pixel Nova","Galaxy Aster","iPhone Lite","Redmi Pulse","OnePlus Drift","Moto Edge"};
    private static final int[] BASE = {260,420,560,190,490,320};

    static class Phone {
        String name; int price, condition;
        Phone(String n,int p,int c){name=n;price=p;condition=c;}
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(18,18,24));
        getWindow().setNavigationBarColor(Color.rgb(18,18,24));
        save=getSharedPreferences("phoneflip_save",MODE_PRIVATE);
        cash=save.getInt("cash",1200); level=save.getInt("level",1); xp=save.getInt("xp",0); day=save.getInt("day",1);
        loadStock();
        buildShell();
        showHome();
    }

    private void loadStock(){
        stock.clear();
        int count=save.getInt("stock_count",0);
        for(int i=0;i<count;i++){
            stock.add(new Phone(save.getString("s_name_"+i,"Phone"),save.getInt("s_price_"+i,100),save.getInt("s_cond_"+i,80)));
        }
    }
    private void persist(){
        SharedPreferences.Editor e=save.edit().putInt("cash",cash).putInt("level",level).putInt("xp",xp).putInt("day",day).putInt("stock_count",stock.size());
        for(int i=0;i<stock.size();i++){Phone p=stock.get(i);e.putString("s_name_"+i,p.name).putInt("s_price_"+i,p.price).putInt("s_cond_"+i,p.condition);}
        e.apply();
    }
    private void buildShell(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(14,14,19));
        TextView brand=label("DTR • PHONE FLIP",20,Color.WHITE,true);brand.setPadding(20,20,20,8);root.addView(brand);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(16,8,16,12);
        ScrollView sc=new ScrollView(this);sc.addView(content);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        nav=new LinearLayout(this);nav.setPadding(8,8,8,12);nav.setBackgroundColor(Color.rgb(24,24,32));
        String[] tabs={"Домой","Рынок","Склад","Игры"};
        for(int i=0;i<tabs.length;i++){final int t=i;Button b=button(tabs[i]);b.setTextSize(12);nav.addView(b,new LinearLayout.LayoutParams(0,52,1));b.setOnClickListener(v->{if(t==0)showHome();if(t==1)showMarket();if(t==2)showStock();if(t==3)showGames();});}
        root.addView(nav);setContentView(root);
    }
    private TextView label(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(93,57,158)));return b;}
    private void title(String s){content.addView(label(s,23,Color.WHITE,true));}
    private void line(String s){TextView t=label(s,15,Color.LTGRAY,false);t.setPadding(0,8,0,8);content.addView(t);}
    private void action(String text,Runnable run){Button b=button(text);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,6,0,6);content.addView(b,p);b.setOnClickListener(v->run.run());}
    private void refresh(Runnable r){persist();r.run();}
    private void showHome(){
        content.removeAllViews();title("Твой магазин");line("День "+day+"  •  Уровень "+level+" ("+xp+" XP)");line("Баланс: $"+cash);line("Телефонов на складе: "+stock.size());
        action("Открыть рынок",this::showMarket);action("Продать телефоны со склада",this::showStock);action("Получить ежедневный бонус",()->{cash+=250+level*25;day++;gain(15);Toast.makeText(this,"Бонус начислен!",Toast.LENGTH_SHORT).show();refresh(this::showHome);});
        line("Сохраняется автоматически на этом устройстве.");
    }
    private void showMarket(){
        content.removeAllViews();title("Рынок телефонов");line("Выбирай телефон, покупай и перепродавай дороже.");
        for(int i=0;i<NAMES.length;i++){final int idx=i;int cond=55+rng.nextInt(46);int price=(int)(BASE[i]*(0.55+cond/220.0));LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(12,10,12,10);card.setBackgroundColor(Color.rgb(27,27,36));TextView tx=label(NAMES[i]+"\nСостояние: "+cond+"%\nЦена: $"+price,16,Color.WHITE,true);card.addView(tx);Button buy=button("Купить");card.addView(buy);buy.setOnClickListener(v->{if(cash<price){Toast.makeText(this,"Не хватает денег",Toast.LENGTH_SHORT).show();return;}cash-=price;stock.add(new Phone(NAMES[idx],price,cond));gain(20);refresh(this::showMarket);});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,6,0,6);content.addView(card,p);}
    }
    private void showStock(){
        content.removeAllViews();title("Мой склад");line("Баланс: $"+cash);
        if(stock.isEmpty())line("Пока пусто. Купи телефон на рынке.");
        for(int i=0;i<stock.size();i++){final int idx=i;Phone ph=stock.get(i);LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(12,10,12,10);card.setBackgroundColor(Color.rgb(27,27,36));card.addView(label(ph.name+"\nСостояние: "+ph.condition+"%\nЗакупка: $"+ph.price,15,Color.WHITE,true));
            Button repair=button("Починить ($80)");card.addView(repair);repair.setOnClickListener(v->{if(cash<80){Toast.makeText(this,"Не хватает денег",Toast.LENGTH_SHORT).show();return;}cash-=80;ph.condition=Math.min(100,ph.condition+25);refresh(this::showStock);});
            Button sell=button("Продать за $"+(int)(ph.price*(0.65+ph.condition/250.0)));card.addView(sell);sell.setOnClickListener(v->{cash+=(int)(ph.price*(0.65+ph.condition/250.0));stock.remove(idx);gain(35);refresh(this::showStock);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,6,0,6);content.addView(card,p);}
    }
    private void showGames(){
        content.removeAllViews();title("Мини-игры");line("Награда — виртуальные деньги, без покупок.");
        action("Угадай число (1–5) — ставка $50",()->{if(cash<50){Toast.makeText(this,"Не хватает денег",Toast.LENGTH_SHORT).show();return;}cash-=50;int answer=1+rng.nextInt(5);int guess=1+rng.nextInt(5);if(answer==guess){cash+=250;gain(25);Toast.makeText(this,"Угадал! +$250",Toast.LENGTH_SHORT).show();}else Toast.makeText(this,"Число было "+answer+". Попробуй ещё!",Toast.LENGTH_SHORT).show();refresh(this::showGames);});
        action("Монетка — ставка $30",()->{if(cash<30){Toast.makeText(this,"Не хватает денег",Toast.LENGTH_SHORT).show();return;}cash-=30;if(rng.nextBoolean()){cash+=60;gain(10);Toast.makeText(this,"Орёл! Выигрыш +$60",Toast.LENGTH_SHORT).show();}else Toast.makeText(this,"Решка — ставка проиграна",Toast.LENGTH_SHORT).show();refresh(this::showGames);});
    }
    private void gain(int n){xp+=n;while(xp>=level*100){xp-=level*100;level++;cash+=level*100;}}
    @Override protected void onPause(){persist();super.onPause();}
    @Override public void onBackPressed(){showHome();}
}
