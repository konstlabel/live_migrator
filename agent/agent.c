/**
 * @file agent.c
 * @brief Native JVMTI agent for heap walking and object tagging.
 *
 * This agent provides native heap walking capabilities for the Live Migrator framework.
 * It uses JVMTI (JVM Tool Interface) to iterate through the heap, tag objects, and
 * resolve tagged objects back to Java references.
 *
 * Key features:
 *   - Epoch-based object tagging for stable identification across GC cycles
 *   - Full heap walk to find all instances of a specific class
 *   - Filtered heap walk for multiple target classes
 *   - Object resolution by tag
 *
 * The agent can be loaded at JVM startup (-agentpath) or attached dynamically
 * to a running JVM via the Attach API.
 *
 * @see migrator.heap.NativeHeapWalker (Java counterpart)
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>
#include <jni.h>
#include <jvmti.h>

static JavaVM* g_vm = NULL;
static jvmtiEnv* g_jvmti = NULL;

/**
 * Epoch-based tagging system.
 * Tags are formed as: (epoch << 32) | local_counter
 * This ensures unique tags across migration cycles.
 */
static volatile jlong g_epoch = 1;
static volatile jlong g_local_counter = 1;

/**
 * Prints JVMTI error information to stderr.
 */
static void check_print(jvmtiEnv* jvmti, jvmtiError err, const char* msg) {
    if (err != JVMTI_ERROR_NONE) {
        char* name = NULL;
        if (jvmti) (*jvmti)->GetErrorName(jvmti, err, &name);
        fprintf(stderr, "[agent] JVMTI error %d (%s): %s\n",
                (int)err,
                name ? name : "UNKNOWN",
                msg);
    }
}

/**
 * Dynamic array for collecting object tags during heap iteration.
 */
typedef struct {
    jlong* tags;
    jint count;
    jint capacity;
    int alloc_failed;
} tag_collector_t;

/**
 * Adds a tag to the collector, growing the array if needed.
 * @return 0 on success, -1 on allocation failure
 */
static int tag_add(tag_collector_t* c, jlong tag) {
    if (c->alloc_failed) return -1;
    if (c->count >= c->capacity) {
        jint newCap = c->capacity == 0 ? 128 : c->capacity * 2;
        jlong* tmp = (jlong*) realloc(c->tags, sizeof(jlong) * (size_t)newCap);
        if (!tmp) {
            c->alloc_failed = 1;
            return -1;
        }
        c->tags = tmp;
        c->capacity = newCap;
    }
    c->tags[c->count++] = tag;
    return 0;
}

/**
 * JVMTI callback to clear all object tags.
 */
static jint JNICALL clear_tag_cb(
        jlong class_tag,
        jlong size,
        jlong* tag_ptr,
        jint length,
        void* user_data) {

    (void) class_tag;
    (void) size;
    (void) length;
    (void) user_data;

    if (tag_ptr && *tag_ptr != 0) {
        *tag_ptr = 0;
    }
    return JVMTI_ITERATION_CONTINUE;
}

/**
 * JVMTI callback to tag heap objects.
 * Assigns a unique tag (epoch:local) to each untagged object.
 * Aborts iteration on allocation failure.
 */
static jint JNICALL heap_tagging_cb(
        jlong class_tag,
        jlong size,
        jlong* tag_ptr,
        jint length,
        void* user_data) {

    (void) class_tag;
    (void) size;
    (void) length;

    tag_collector_t* col = (tag_collector_t*) user_data;
    if (!tag_ptr) return JVMTI_ITERATION_CONTINUE;

    if (*tag_ptr != 0) {
        return JVMTI_ITERATION_CONTINUE;
    }

    jlong local = __sync_fetch_and_add(&g_local_counter, 1);
    uint64_t tag64 = (((uint64_t)(uint32_t)g_epoch) << 32) | ((uint64_t)local & 0xFFFFFFFFULL);
    jlong tag = (jlong) tag64;

    *tag_ptr = tag;

    if (tag_add(col, tag) != 0) {
        return JVMTI_ITERATION_ABORT;
    }

    return JVMTI_ITERATION_CONTINUE;
}

/**
 * Clears all object tags in the heap.
 */
static void clear_all_tags() {
    if (!g_jvmti) return;
    jvmtiHeapCallbacks cb;
    memset(&cb, 0, sizeof(cb));
    cb.heap_iteration_callback = &clear_tag_cb;
    jvmtiError err = (*g_jvmti)->IterateThroughHeap(g_jvmti, JVMTI_HEAP_OBJECT_EITHER, NULL, &cb, NULL);
    check_print(g_jvmti, err, "IterateThroughHeap(clear) failed");
}

/**
 * Creates a heap snapshot for objects of a specific class.
 *
 * Returns a byte array containing:
 *   - 4 bytes: object count (big-endian)
 *   - For each object:
 *     - 8 bytes: tag (big-endian)
 *     - 4 bytes: class name length (big-endian)
 *     - N bytes: class name (UTF-8)
 *
 * @param jClassName Internal class name (e.g., "service/model/OldUser")
 * @return Byte array with snapshot data, or NULL on error
 */
#undef Java_migrator_heap_NativeHeapWalker_nativeSnapshotBytes
JNIEXPORT jbyteArray JNICALL
Java_migrator_heap_NativeHeapWalker_nativeSnapshotBytes(
        JNIEnv* env,
        jclass cls,
        jstring jClassName) {

    (void) cls;

    if (!g_jvmti || !env || !jClassName) return NULL;

    clear_all_tags();
    __sync_synchronize();
    g_local_counter = 1;

    const char* className = (*env)->GetStringUTFChars(env, jClassName, NULL);
    if (!className) return NULL;

    jclass targetClass = (*env)->FindClass(env, className);
    if (!targetClass) {
        (*env)->ReleaseStringUTFChars(env, jClassName, className);
        return NULL;
    }

    tag_collector_t col;
    memset(&col, 0, sizeof(col));

    jvmtiHeapCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.heap_iteration_callback = &heap_tagging_cb;

    jvmtiError err = (*g_jvmti)->IterateThroughHeap(g_jvmti, JVMTI_HEAP_OBJECT_EITHER, targetClass, &callbacks, &col);
    if (err != JVMTI_ERROR_NONE) {
        check_print(g_jvmti, err, "IterateThroughHeap(tagging) failed");
    }

    jint count = col.count;
    jint found = 0;
    jobject* objects = NULL;
    jlong* tagsOut = NULL;
    if (count > 0) {
        err = (*g_jvmti)->GetObjectsWithTags(g_jvmti, count, col.tags, &found, &objects, &tagsOut);
        if (err != JVMTI_ERROR_NONE) {
            check_print(g_jvmti, err, "GetObjectsWithTags failed");
            found = 0;
            objects = NULL;
            tagsOut = NULL;
        }
    }

    jclass classClass = (*env)->FindClass(env, "java/lang/Class");
    if (classClass == NULL) {
        if (objects) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        if (col.tags) free(col.tags);
        (*env)->ReleaseStringUTFChars(env, jClassName, className);
        return NULL;
    }
    jmethodID getName = (*env)->GetMethodID(env, classClass, "getName", "()Ljava/lang/String;");
    if (getName == NULL) {
        if (objects) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        if (col.tags) free(col.tags);
        (*env)->ReleaseStringUTFChars(env, jClassName, className);
        return NULL;
    }

    char** names = NULL;
    int* lens = NULL;
    size_t total = 4;

    if (found > 0) {
        names = (char**) calloc((size_t)found, sizeof(char*));
        lens = (int*) calloc((size_t)found, sizeof(int));
        if (!names || !lens) {
            check_print(g_jvmti, JVMTI_ERROR_OUT_OF_MEMORY, "calloc failed for names/lens");
            if (names) free(names);
            if (lens) free(lens);
            if (objects) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
            if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
            if (col.tags) free(col.tags);
            (*env)->ReleaseStringUTFChars(env, jClassName, className);
            return NULL;
        }
    }

    for (jint i = 0; i < found; i++) {
        jobject obj = objects[i];
        if (obj == NULL) {
            names[i] = NULL;
            lens[i] = 0;
            continue;
        }

        jclass oc = (*env)->GetObjectClass(env, obj);
        if (oc == NULL) {
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            names[i] = NULL;
            lens[i] = 0;
            (*env)->DeleteLocalRef(env, obj);
            continue;
        }

        jstring jn = (jstring)(*env)->CallObjectMethod(env, oc, getName);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            (*env)->DeleteLocalRef(env, oc);
            (*env)->DeleteLocalRef(env, obj);
            names[i] = NULL;
            lens[i] = 0;
            continue;
        }

        const char* cn = (*env)->GetStringUTFChars(env, jn, NULL);
        if (!cn) {
            (*env)->DeleteLocalRef(env, jn);
            (*env)->DeleteLocalRef(env, oc);
            (*env)->DeleteLocalRef(env, obj);
            names[i] = NULL;
            lens[i] = 0;
            continue;
        }

        int len = (int) strlen(cn);
        char* copy = NULL;
        if (len > 0) {
            copy = (char*) malloc((size_t)len);
            if (!copy) {
                (*env)->ReleaseStringUTFChars(env, jn, cn);
                (*env)->DeleteLocalRef(env, jn);
                (*env)->DeleteLocalRef(env, oc);
                (*env)->DeleteLocalRef(env, obj);
                check_print(g_jvmti, JVMTI_ERROR_OUT_OF_MEMORY, "malloc for name copy failed");
                for (jint k = 0; k < i; k++) if (names[k]) free(names[k]);
                free(names);
                free(lens);
                if (objects) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
                if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
                if (col.tags) free(col.tags);
                (*env)->ReleaseStringUTFChars(env, jClassName, className);
                return NULL;
            }
            memcpy(copy, cn, (size_t)len);
        } else {
            copy = NULL;
        }

        names[i] = copy;
        lens[i] = len;
        total += 8 + 4 + (size_t)len;

        (*env)->ReleaseStringUTFChars(env, jn, cn);
        (*env)->DeleteLocalRef(env, jn);
        (*env)->DeleteLocalRef(env, oc);
        (*env)->DeleteLocalRef(env, obj);
    }

    if (total > (size_t)INT_MAX) {
        check_print(g_jvmti, JVMTI_ERROR_INTERNAL, "Snapshot size exceeds INT_MAX");
        for (jint i = 0; i < found; i++) if (names && names[i]) free(names[i]);
        if (names) free(names);
        if (lens) free(lens);
        if (objects) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        if (col.tags) free(col.tags);
        (*env)->ReleaseStringUTFChars(env, jClassName, className);
        return NULL;
    }

    size_t bufSize = total;
    unsigned char* buf = (unsigned char*) malloc(bufSize);
    if (!buf) {
        check_print(g_jvmti, JVMTI_ERROR_OUT_OF_MEMORY, "malloc for output buffer failed");
        for (jint i = 0; i < found; i++) if (names && names[i]) free(names[i]);
        if (names) free(names);
        if (lens) free(lens);
        if (objects) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        if (col.tags) free(col.tags);
        (*env)->ReleaseStringUTFChars(env, jClassName, className);
        return NULL;
    }

    unsigned char* p = buf;
    jint be_count = found;
    p[0] = (unsigned char)((be_count >> 24) & 0xFF);
    p[1] = (unsigned char)((be_count >> 16) & 0xFF);
    p[2] = (unsigned char)((be_count >> 8) & 0xFF);
    p[3] = (unsigned char)(be_count & 0xFF);
    p += 4;

    for (jint i = 0; i < found; i++) {
        jlong t = tagsOut ? tagsOut[i] : 0;
        uint64_t ut = (uint64_t) t;
        for (int b = 7; b >= 0; b--) {
            p[b] = (unsigned char)(ut & 0xFF);
            ut >>= 8;
        }
        p += 8;

        int l = lens[i];
        p[0] = (unsigned char)((l >> 24) & 0xFF);
        p[1] = (unsigned char)((l >> 16) & 0xFF);
        p[2] = (unsigned char)((l >> 8) & 0xFF);
        p[3] = (unsigned char)(l & 0xFF);
        p += 4;

        if (l > 0 && names[i] != NULL) {
            memcpy(p, names[i], (size_t)l);
            p += l;
        }
    }

    jbyteArray out = (*env)->NewByteArray(env, (jsize) bufSize);
    if (!out) {
        check_print(g_jvmti, JVMTI_ERROR_OUT_OF_MEMORY, "NewByteArray failed");
        free(buf);
        for (jint i = 0; i < found; i++) if (names && names[i]) free(names[i]);
        if (names) free(names);
        if (lens) free(lens);
        if (objects) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        if (col.tags) free(col.tags);
        (*env)->ReleaseStringUTFChars(env, jClassName, className);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) bufSize, (jbyte*) buf);
    free(buf);

    for (jint i = 0; i < found; i++) if (names && names[i]) free(names[i]);
    if (names) free(names);
    if (lens) free(lens);

    if (objects) {
        jvmtiError derr = (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        check_print(g_jvmti, derr, "Deallocate(objects) failed");
    }
    if (tagsOut) {
        jvmtiError derr = (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        check_print(g_jvmti, derr, "Deallocate(tagsOut) failed");
    }
    if (col.tags) free(col.tags);

    (*env)->ReleaseStringUTFChars(env, jClassName, className);
    return out;
}

/**
 * Resolves a tagged object back to a Java reference.
 *
 * @param tag The object tag assigned during heap snapshot
 * @return The Java object, or NULL if not found or GC'd
 */
JNIEXPORT jobject JNICALL
Java_migrator_heap_NativeHeapWalker_nativeResolve(
        JNIEnv* env,
        jclass cls,
        jlong tag) {

    (void) cls;
    if (!g_jvmti || !env) return NULL;

    jobject* objs = NULL;
    jint count = 0;
    jlong jtag = tag;
    jvmtiError err = (*g_jvmti)->GetObjectsWithTags(g_jvmti, 1, &jtag, &count, &objs, NULL);
    if (err != JVMTI_ERROR_NONE) {
        check_print(g_jvmti, err, "GetObjectsWithTags(nativeResolve) failed");
        if (objs) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objs);
        return NULL;
    }

    jobject res = NULL;
    if (count > 0 && objs != NULL) {
        res = objs[0];
        for (jint i = 1; i < count; i++) {
            if (objs[i]) (*env)->DeleteLocalRef(env, objs[i]);
        }
    }

    if (objs) {
        jvmtiError derr = (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objs);
        check_print(g_jvmti, derr, "Deallocate(nativeResolve objs) failed");
    }

    return res;
}

/**
 * Walks the entire heap and returns all objects.
 *
 * @return Array of all objects on the heap, or NULL on error
 */
JNIEXPORT jobjectArray JNICALL
Java_migrator_heap_NativeHeapWalker_nativeWalkHeap(JNIEnv *env, jobject thisObj) {
    (void)thisObj;

    if (!g_jvmti || !env) return NULL;

    clear_all_tags();
    __sync_synchronize();
    g_local_counter = 1;

    tag_collector_t col;
    memset(&col, 0, sizeof(col));

    jvmtiHeapCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.heap_iteration_callback = &heap_tagging_cb;

    jvmtiError err = (*g_jvmti)->IterateThroughHeap(g_jvmti, JVMTI_HEAP_OBJECT_EITHER, NULL, &callbacks, &col);
    if (err != JVMTI_ERROR_NONE) {
        check_print(g_jvmti, err, "IterateThroughHeap in nativeWalkHeap failed");
    }

    jint count = col.count;
    if (count <= 0) {
        if (col.tags) free(col.tags);
        return NULL;
    }

    jint found = 0;
    jobject* objects = NULL;
    jlong* tagsOut = NULL;

    err = (*g_jvmti)->GetObjectsWithTags(g_jvmti, count, col.tags, &found, &objects, &tagsOut);
    if (err != JVMTI_ERROR_NONE) {
        check_print(g_jvmti, err, "GetObjectsWithTags(nativeWalkHeap) failed");
        if (col.tags) free(col.tags);
        return NULL;
    }

    jclass objClass = (*env)->FindClass(env, "java/lang/Object");
    if (objClass == NULL) {
        if (objects) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        if (col.tags) free(col.tags);
        return NULL;
    }

    jobjectArray result = (*env)->NewObjectArray(env, found, objClass, NULL);
    if (result == NULL) {
        if (objects) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        if (col.tags) free(col.tags);
        return NULL;
    }

    for (jint i = 0; i < found; i++) {
        jobject o = objects[i];
        (*env)->SetObjectArrayElement(env, result, i, o);
        if (o) (*env)->DeleteLocalRef(env, o);
    }

    if (objects) {
        jvmtiError derr = (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        check_print(g_jvmti, derr, "Deallocate(objects) failed");
    }
    if (tagsOut) {
        jvmtiError derr = (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        check_print(g_jvmti, derr, "Deallocate(tagsOut) failed");
    }
    if (col.tags) free(col.tags);

    return result;
}

/**
 * Walks the heap filtered by specific class names.
 * More efficient than full heap walk when only specific classes are needed.
 *
 * @param classNamesArray Array of internal class names (e.g., "service/model/OldUser")
 * @return Array of objects matching the specified classes, or NULL on error
 */
JNIEXPORT jobjectArray JNICALL
Java_migrator_heap_NativeHeapWalker_nativeWalkHeapFiltered(
    JNIEnv *env, jclass cls, jobjectArray classNamesArray) {

    (void) cls;

    if (!g_jvmti || !env || classNamesArray == NULL) return NULL;

    jsize nClasses = (*env)->GetArrayLength(env, classNamesArray);
    if (nClasses == 0) return NULL;

    jobject *collected = NULL;
    jint collectedCount = 0;
    jint collectedCap = 0;

    for (jsize ci = 0; ci < nClasses; ci++) {
        jstring jName = (jstring)(*env)->GetObjectArrayElement(env, classNamesArray, ci);
        if (jName == NULL) continue;

        const char* cname = (*env)->GetStringUTFChars(env, jName, NULL);
        if (cname == NULL) {
            (*env)->DeleteLocalRef(env, jName);
            continue;
        }

        jclass targetClass = (*env)->FindClass(env, cname);
        (*env)->ReleaseStringUTFChars(env, jName, cname);
        (*env)->DeleteLocalRef(env, jName);

        if (targetClass == NULL) {
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
            continue;
        }

        __sync_fetch_and_add(&g_epoch, 1);
        __sync_synchronize();
        g_local_counter = 1;

        tag_collector_t col;
        memset(&col, 0, sizeof(col));

        jvmtiHeapCallbacks callbacks;
        memset(&callbacks, 0, sizeof(callbacks));
        callbacks.heap_iteration_callback = &heap_tagging_cb;

        jvmtiError err = (*g_jvmti)->IterateThroughHeap(g_jvmti, JVMTI_HEAP_OBJECT_EITHER, targetClass, &callbacks, &col);
        if (err != JVMTI_ERROR_NONE) {
            check_print(g_jvmti, err, "IterateThroughHeap failed for class");
            if (col.tags) free(col.tags);
            (*env)->DeleteLocalRef(env, targetClass);
            continue;
        }

        jint count = col.count;
        if (count <= 0) {
            if (col.tags) free(col.tags);
            (*env)->DeleteLocalRef(env, targetClass);
            continue;
        }

        jint found = 0;
        jobject *objs = NULL;
        jlong *tagsOut = NULL;

        err = (*g_jvmti)->GetObjectsWithTags(g_jvmti, count, col.tags, &found, &objs, &tagsOut);
        if (err != JVMTI_ERROR_NONE) {
            check_print(g_jvmti, err, "GetObjectsWithTags failed for class");
            if (col.tags) free(col.tags);
            (*env)->DeleteLocalRef(env, targetClass);
            continue;
        }

        if (found > 0 && objs != NULL) {
            if (collectedCount + found > collectedCap) {
                jint newCap = collectedCap == 0 ? (found + 64) : (collectedCap * 2 + found);
                jobject *tmp = (jobject*) realloc(collected, sizeof(jobject) * (size_t)newCap);
                if (!tmp) {
                    for (jint i = 0; i < found; i++) {
                        if (objs[i]) (*env)->DeleteLocalRef(env, objs[i]);
                    }
                    if (objs) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objs);
                    if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
                    if (col.tags) free(col.tags);
                    free(collected);
                    (*env)->DeleteLocalRef(env, targetClass);
                    return NULL;
                }
                collected = tmp;
                collectedCap = newCap;
            }

            for (jint i = 0; i < found; i++) {
                collected[collectedCount++] = objs[i];
            }
        }

        if (objs) {
            jvmtiError derr = (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objs);
            check_print(g_jvmti, derr, "Deallocate(objects) failed");
        }
        if (tagsOut) {
            jvmtiError derr = (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
            check_print(g_jvmti, derr, "Deallocate(tagsOut) failed");
        }
        if (col.tags) free(col.tags);

        (*env)->DeleteLocalRef(env, targetClass);
    }

    if (collectedCount == 0) {
        if (collected) free(collected);
        return NULL;
    }

    jclass objClass = (*env)->FindClass(env, "java/lang/Object");
    if (objClass == NULL) {
        for (jint i = 0; i < collectedCount; i++) {
            if (collected[i]) (*env)->DeleteLocalRef(env, collected[i]);
        }
        free(collected);
        return NULL;
    }

    jobjectArray result = (*env)->NewObjectArray(env, collectedCount, objClass, NULL);
    if (result == NULL) {
        for (jint i = 0; i < collectedCount; i++) {
            if (collected[i]) (*env)->DeleteLocalRef(env, collected[i]);
        }
        free(collected);
        return NULL;
    }

    for (jint i = 0; i < collectedCount; i++) {
        jobject o = collected[i];
        (*env)->SetObjectArrayElement(env, result, i, o);
        if (o) (*env)->DeleteLocalRef(env, o);
    }

    free(collected);
    return result;
}

/**
 * Advances the epoch counter.
 * Called after migration completes to invalidate old tags.
 */
JNIEXPORT void JNICALL
Java_migrator_heap_NativeHeapWalker_nativeAdvanceEpoch(
        JNIEnv* env,
        jclass cls) {
    (void) env;
    (void) cls;
    __sync_fetch_and_add(&g_epoch, 1);
}

/**
 * Initializes the agent by obtaining JVMTI environment and requesting capabilities.
 */
static jint agent_start(JavaVM* vm) {
    if (!vm) return JNI_ERR;
    g_vm = vm;

    jint getenv_res = (*vm)->GetEnv(vm, (void**)&g_jvmti, JVMTI_VERSION_1_2);
    if (getenv_res != JNI_OK || g_jvmti == NULL) {
        fprintf(stderr, "[agent] Failed to get JVMTI env\n");
        return JNI_ERR;
    }

    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_tag_objects = 1;
    jvmtiError err = (*g_jvmti)->AddCapabilities(g_jvmti, &caps);
    if (err != JVMTI_ERROR_NONE) {
        check_print(g_jvmti, err, "AddCapabilities failed");
        return JNI_ERR;
    }

    return JNI_OK;
}

/**
 * Agent entry point for JVM startup (-agentpath).
 */
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved) {
    (void) options;
    (void) reserved;
    return agent_start(vm);
}

/**
 * Agent entry point for dynamic attach (Attach API).
 */
JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void* reserved) {
    (void) options;
    (void) reserved;
    return agent_start(vm);
}
