#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"
int main(int argc, char** argv)
{
 int fd; char* fn;
 fn = "myfile.txt";
 fd = creat(fn); // similarly replace with open()
 halt(); //can change this line to return 0; after implementing exit() sys call.
}