"""Build the experimental ARMv7 Android adapter on Windows without Gradle.
Set ANDROID_SDK_ROOT, ANDROID_NDK_HOME, JAVA_HOME. Requires build-tools 35.0.1,
platform android-35, NDK r27d and JDK 21. Outputs are written to build/.
"""
import os
import pathlib
import subprocess
import zipfile
import shutil
import tempfile

ROOT=pathlib.Path(__file__).resolve().parent
SDK=pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
NDK=pathlib.Path(os.environ['ANDROID_NDK_HOME'])
JDK=pathlib.Path(os.environ['JAVA_HOME'])
BUILD=ROOT/'build'
TOOLS=SDK/'build-tools/35.0.1'
ANDROID=SDK/'platforms/android-35/android.jar'

def run(args):
    print('Running:',pathlib.Path(str(args[0])).name,flush=True)
    subprocess.run([str(x) for x in args],check=True,cwd=ROOT)

def main():
    run([__import__('sys').executable,ROOT/'prepare_shaders.py'])
    for name in ['classes','dex','aar','native']:(BUILD/name).mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(ROOT/'third_party/SDL3-3.4.20.aar') as archive:archive.extractall(BUILD/'aar')
    sdl=BUILD/'aar/prefab/modules/SDL3-shared/libs/android.armeabi-v7a/libSDL3.so'
    dest=ROOT/'libs/armeabi-v7a'
    dest.mkdir(parents=True,exist_ok=True)
    shutil.copy2(sdl,dest/'libSDL3.so')
    compiler=NDK/'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
    run([compiler,'--target=armv7a-linux-androideabi21','-std=c++17','-fPIC','-shared','-O1','-g',
         '-static-libstdc++','-Wl,-z,max-page-size=16384','-Wl,-soname,libmain.so',
         '-I',ROOT/'third_party/SDL-include',
         *sorted((ROOT/'native').glob('*.cpp')),
         '-L',dest,'-lSDL3','-ldl','-llog','-landroid','-lGLESv2','-lm','-latomic','-lz',
         '-o',dest/'libmain.so'])
    sources=sorted((ROOT/'java').rglob('*.java'))
    run([JDK/'bin/javac.exe','-encoding','UTF-8','-source','8','-target','8','-bootclasspath',ANDROID,
         '-classpath',BUILD/'aar/classes.jar','-d',BUILD/'classes',*sources])
    classes=BUILD/'app-classes.jar'
    with zipfile.ZipFile(classes,'w') as jar:
        for file in (BUILD/'classes').rglob('*.class'):jar.write(file,file.relative_to(BUILD/'classes').as_posix())
    run([JDK/'bin/java.exe','-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--min-api','21',
         '--lib',ANDROID,'--output',BUILD/'dex',classes,BUILD/'aar/classes.jar'])
    unsigned=BUILD/'unsigned.apk'
    run([TOOLS/'aapt.exe','package','-f','-M',ROOT/'AndroidManifest.xml','-I',ANDROID,
         '-S',ROOT/'res','-A',ROOT/'assets','-F',unsigned])
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_STORED) as archive:
        archive.write(BUILD/'dex/classes.dex','classes.dex')
        for file in (ROOT/'libs').rglob('*.so'):archive.write(file,'lib/'+file.relative_to(ROOT/'libs').as_posix())
    key=BUILD/'development-key.p12'
    if not key.exists():
        run([JDK/'bin/keytool.exe','-genkeypair','-keystore',key,'-storepass','android','-keypass','android',
             '-alias','hebdomad-development','-dname','CN=Hebdomad experimental adapter','-keyalg','RSA','-keysize','2048','-validity','3650'])
    apk=BUILD/'Hebdomad-experimental.apk'
    with tempfile.TemporaryDirectory(dir=BUILD) as staging:
        aligned=pathlib.Path(staging)/'aligned.apk'
        signed=pathlib.Path(staging)/apk.name
        run([TOOLS/'zipalign.exe','-P','16','4',unsigned,aligned])
        run([JDK/'bin/java.exe','-jar',TOOLS/'lib/apksigner.jar','sign','--ks',key,'--ks-pass','pass:android',
             '--v4-signing-enabled','false','--out',signed,aligned])
        run([JDK/'bin/java.exe','-jar',TOOLS/'lib/apksigner.jar','verify','--verbose',signed])
        signed.replace(apk)
    print('Built:',apk,flush=True)

if __name__=='__main__':main()
