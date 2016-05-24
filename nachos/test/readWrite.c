#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"

#define BUFSIZE 1024
char buf[BUFSIZE];

int main(int argc, char** argv)
{
 int fd, amount; char* fn;
 fn = "myfile.txt"; //this file should have some text
 fd = open(fn);
 if (fd != -1){
  while ((amount = read(fd, buf, BUFSIZE))>0) {
    write(1, buf, amount); // 1 is STDOUT
  }
 close(fd);
 } else {
   printf("Unable to open file: %s\n", fn);
 }
 halt();
}