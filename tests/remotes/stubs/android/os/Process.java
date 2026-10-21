package android.os;
public class Process { public static int myPid() { return (int) ProcessHandle.current().pid(); } }
