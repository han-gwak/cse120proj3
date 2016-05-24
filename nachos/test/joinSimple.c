#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"
int main(int argc, char** argv)
{
 int fd; char* fn; char* argv2[2];
 int c, cid, cstatus;
 fn = "loopSimple.coff";
 argv2[0] = fn;
 c=1;
 cid = exec(fn, c,argv2);
 join(cid, &cstatus);
 printf("Child pid=%d, exit status: %d\n", cid, cstatus);
 while(c++ < 15) {
 printf("c=%d\n", c);
 }
 return 0;
}