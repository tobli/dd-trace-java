package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Arrays;
import java.util.Objects;

/** Shares type information using a single cache across multiple classloaders. */
public final class TypeInfoCache<T> {
  public static final URL UNKNOWN_CLASS_FILE = null;

  // limit allowed capacities as descriptions are not small
  private static final int MAX_CAPACITY = 1 << 16;
  private static final int MIN_CAPACITY = 1 << 4;

  private static final int MAX_HASH_ATTEMPTS = 5;

  private final SharedTypeInfo<T>[] sharedTypeInfo;
  private final int slotMask;

  @SuppressWarnings("unchecked")
  public TypeInfoCache(int capacity) {
    if (capacity < MIN_CAPACITY) {
      capacity = MIN_CAPACITY;
    } else if (capacity > MAX_CAPACITY) {
      capacity = MAX_CAPACITY;
    }
    // choose enough slot bits to cover the chosen capacity
    this.slotMask = 0xFFFFFFFF >>> Integer.numberOfLeadingZeros(capacity - 1);
    this.sharedTypeInfo = new SharedTypeInfo[slotMask + 1];
  }

  /**
   * Finds the most recently shared information for the named type.
   *
   * <p>When multiple types exist with the same name only one type will be cached at a time. Callers
   * can compare the originating classloader and class file resource to help disambiguate results.
   */
  public SharedTypeInfo<T> find(String className) {
    int nameHash = className.hashCode();
    for (int i = 1; true; i++) {
      SharedTypeInfo<T> value = sharedTypeInfo[slotMask & nameHash];
      if (null == value) {
        return null;
      } else if (className.equals(value.className)) {
        value.lastUsed = System.currentTimeMillis();
        return value;
      } else if (i == MAX_HASH_ATTEMPTS) {
        return null;
      }
      nameHash = rehash(nameHash);
    }
  }

  /**
   * Shares information for the named type, replacing any previously shared details.
   *
   * @return previously shared information for the named type
   */
  public SharedTypeInfo<T> share(String className, ClassLoader loader, URL classFile, T typeInfo) {
    SharedTypeInfo<T> newValue =
        new SharedTypeInfo<>(className, loaderId(loader), classFile, typeInfo);

    int nameHash = className.hashCode();
    int slot = slotMask & nameHash;

    long leastUsedTime = Long.MAX_VALUE;
    int leastUsedSlot = slot;

    for (int i = 1; true; i++) {
      SharedTypeInfo<T> oldValue = sharedTypeInfo[slot];
      if (null == oldValue || className.equals(oldValue.className)) {
        sharedTypeInfo[slot] = newValue;
        return oldValue;
      } else if (i == MAX_HASH_ATTEMPTS) {
        sharedTypeInfo[leastUsedSlot] = newValue; // overwrite least-recently used
        return null;
      } else if (oldValue.lastUsed < leastUsedTime) {
        leastUsedTime = oldValue.lastUsed;
        leastUsedSlot = slot;
      }
      nameHash = rehash(nameHash);
      slot = slotMask & nameHash;
    }
  }

  /** Clears all type information from the shared cache. */
  public void clear() {
    Arrays.fill(sharedTypeInfo, null);
  }

  private static int rehash(int oldHash) {
    return Integer.reverseBytes(oldHash * 0x9e3775cd) * 0x9e3775cd;
  }

  private static LoaderId loaderId(ClassLoader loader) {
    return BOOTSTRAP_LOADER == loader
        ? BOOTSTRAP_LOADER_ID
        : loaderIds.computeIfAbsent(loader, LoaderId::new);
  }

  static final ClassLoader BOOTSTRAP_LOADER = null;
  static final LoaderId BOOTSTRAP_LOADER_ID = null;

  private static final DDCache<ClassLoader, LoaderId> loaderIds =
      DDCaches.newFixedSizeWeakKeyCache(64);

  /** Supports classloader comparisons without strongly referencing the classloader. */
  private static final class LoaderId extends WeakReference<ClassLoader> {
    private final int loaderHash;

    LoaderId(ClassLoader loader) {
      super(loader);
      this.loaderHash = System.identityHashCode(loader);
    }

    boolean sameClassLoader(ClassLoader loader) {
      return loaderHash == System.identityHashCode(loader) && loader == get();
    }
  }

  /** Wraps type information with the classloader and class file resource it originated from. */
  public static final class SharedTypeInfo<T> {
    final String className;

    private final LoaderId loaderId;
    private final URL classFile;
    private final T typeInfo;

    long lastUsed = System.currentTimeMillis();

    SharedTypeInfo(String className, LoaderId loaderId, URL classFile, T typeInfo) {
      this.className = className;
      this.loaderId = loaderId;
      this.classFile = classFile;
      this.typeInfo = typeInfo;
    }

    public boolean sameClassLoader(ClassLoader loader) {
      return BOOTSTRAP_LOADER_ID == loaderId
          ? BOOTSTRAP_LOADER == loader
          : loaderId.sameClassLoader(loader);
    }

    public boolean sameClassFile(URL classFile) {
      return UNKNOWN_CLASS_FILE != classFile
          && UNKNOWN_CLASS_FILE != this.classFile
          && sameClassFile(this.classFile, classFile);
    }

    public T get() {
      return typeInfo;
    }

    /** Matches class file resources without triggering network lookups. */
    private static boolean sameClassFile(URL lhs, URL rhs) {
      return Objects.equals(lhs.getFile(), rhs.getFile())
          && Objects.equals(lhs.getRef(), rhs.getRef())
          && Objects.equals(lhs.getAuthority(), rhs.getAuthority())
          && Objects.equals(lhs.getProtocol(), rhs.getProtocol());
    }
  }
}
