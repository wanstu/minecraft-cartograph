package com.wanstu.cartograph.v4;

public final class CaptureWorkBudgetTest {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        var budget=new CaptureWorkBudget();
        check(budget.level()==0&&budget.columns()==256&&budget.archiveBatch()==16&&budget.archivePeriod()==2,"normal");
        budget.sample(48_000_000,0);budget.sample(48_000_000,0);
        check(budget.level()==0,"transient");
        budget.sample(48_000_000,0);
        check(budget.level()==1&&budget.columns()==128&&budget.archiveBatch()==4&&!budget.maintenanceAllowed(),"moderate");
        for(int i=0;i<3;i++)budget.sample(48_000_000,0);
        check(budget.level()==2&&budget.columns()==64&&budget.nanos()==250_000,"high load");
        check(budget.archiveDue(40,0)&&!budget.archiveDue(40,1)&&!budget.archiveDue(39,0),"bounded archive progress when idle");
        for(int i=0;i<99;i++)budget.sample(20_000_000,0);
        check(budget.level()==2,"hysteresis");
        budget.sample(20_000_000,0);check(budget.level()==1,"partial recovery");
        for(int i=0;i<100;i++)budget.sample(20_000_000,0);
        check(budget.level()==0,"recovery");
        budget.sample(10_000_000,32);check(budget.level()==2,"queue emergency independent of tick load");
        for(int i=0;i<1000;i++)budget.sample(40_000_000,3);
        check(budget.level()==2,"no premature recovery");
        System.out.println("PASS: server tick and queue pressure, bounded live and archive budgets, recovery hysteresis, emergency queue cap");
    }
}
