#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"
int main(int argc, char** argv)
{
 char* fn; char* argv2[1];
 int c, cid;
 fn = "loopSimple.coff";
 argv2[0] = fn;
 c = 1;
 cid = exec(fn, c,argv2);
 while(c++ < 15) {
    printf("c=%d\n", c);
 }
 return 0;
}