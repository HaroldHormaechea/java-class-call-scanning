package com.hhg.main.targets;

public class TargetClass1 {

    private final TargetClass2 tc2 = new TargetClass2();

    public void methodInClass1(){
        tc2.methodInClass2();
    }
}
