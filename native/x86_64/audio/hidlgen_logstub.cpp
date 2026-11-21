#include <android/log.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
static __android_logger_function g_logger; static __android_aborter_function g_abort; static int g_min = ANDROID_LOG_INFO;
extern "C" {
int __android_log_write(int p,const char*t,const char*m){fprintf(stderr,"%s: %s\n",t?t:"",m);return 0;}
int __android_log_print(int p,const char*t,const char*f,...){va_list a;va_start(a,f);fprintf(stderr,"%s: ",t?t:"");vfprintf(stderr,f,a);fputc('\n',stderr);va_end(a);return 0;}
void __android_log_assert(const char*c,const char*t,const char*f,...){fprintf(stderr,"assert %s\n",t?t:"");abort();}
int __android_log_buf_write(int b,int p,const char*t,const char*m){return __android_log_write(p,t,m);}
void __android_log_set_logger(__android_logger_function f){g_logger=f;}
void __android_log_set_aborter(__android_aborter_function f){g_abort=f;}
void __android_log_call_aborter(const char*m){if(g_abort)g_abort(m);else abort();}
void __android_log_write_log_message(__android_log_message*m){fprintf(stderr,"%s: %s\n",m->tag?m->tag:"",m->message?m->message:"");}
int __android_log_get_minimum_priority(){return g_min;}
int __android_log_set_minimum_priority(int p){int o=g_min;g_min=p;return o;}
int __android_log_is_loggable(int prio,const char*tag,int def){return prio>=g_min;}
}
extern "C" {
void __android_log_set_default_tag(const char*t){}
void __android_log_logd_logger(const struct __android_log_message*m){}
}
extern "C" {
int __android_log_buf_print(int b,int p,const char*t,const char*f,...){va_list a;va_start(a,f);fprintf(stderr,"%s: ",t?t:"");vfprintf(stderr,f,a);fputc('\n',stderr);va_end(a);return 0;}
void __android_log_stderr_logger(const struct __android_log_message*m){}
void __android_log_default_aborter(const char*m){abort();}
}
