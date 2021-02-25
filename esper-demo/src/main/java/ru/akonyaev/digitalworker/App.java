package ru.akonyaev.digitalworker;

import java.util.Scanner;

public final class App {
    public static void main(String[] args) {
        EsperRunner esperRunner = new EsperRunner();
        System.out.println("Press Enter to exit");
        new Scanner(System.in).nextLine();
        esperRunner.destroy();
    }
}
