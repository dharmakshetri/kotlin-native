/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.InteropLibrary
import org.jetbrains.kotlin.backend.konan.KonanBuiltIns
import org.jetbrains.kotlin.backend.konan.KonanConfigKeys
import org.jetbrains.kotlin.backend.konan.library.LinkData
import org.jetbrains.kotlin.backend.konan.llvm.base64Decode
import org.jetbrains.kotlin.backend.konan.llvm.base64Encode
import org.jetbrains.kotlin.backend.konan.llvm.isExported
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.descriptors.IrTemporaryVariableDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.CompilerDeserializationConfiguration
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.serialization.KonanDescriptorSerializer
import org.jetbrains.kotlin.serialization.KonanLinkData
import org.jetbrains.kotlin.serialization.KonanLinkData.*
import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.serialization.deserialization.*
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.SimpleType
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/*
 * This is Konan specific part of public descriptor 
 * tree serialization and deserialization.
 *
 * It takes care of module and package fragment serializations.
 * The lower level (classes and members) serializations are delegated 
 * to the KonanDescriptorSerializer class.
 * The lower level deserializations are performed by the frontend
 * with MemberDeserializer class.
 */

/* ------------ Deserializer part ------------------------------------------*/

object NullFlexibleTypeDeserializer : FlexibleTypeDeserializer {
    override fun create(proto: ProtoBuf.Type, flexibleId: String, 
        lowerBound: SimpleType, upperBound: SimpleType): KotlinType {
            error("Illegal use of flexible type deserializer.")
        }
}

fun createKonanPackageFragmentProvider(
        fragmentNames: List<String>,
        packageLoader: (String)->KonanLinkData.PackageFragment,
        storageManager: StorageManager, module: ModuleDescriptor,
        configuration: DeserializationConfiguration,
        interopLibrary: InteropLibrary?): PackageFragmentProvider {

    val packageFragments = fragmentNames.map{ 
        KonanPackageFragment(it, packageLoader, storageManager, module) 
    }
    val syntheticInteropPackageFragments =
            interopLibrary?.createSyntheticPackages(module, packageFragments) ?: emptyList()

    val provider = PackageFragmentProviderImpl(packageFragments + syntheticInteropPackageFragments)

    val notFoundClasses = NotFoundClasses(storageManager, module)

    val annotationAndConstantLoader = AnnotationAndConstantLoaderImpl(module, notFoundClasses, KonanSerializerProtocol)

    val components = DeserializationComponents(
        storageManager, module, configuration, 
        DeserializedClassDataFinder(provider),
        annotationAndConstantLoader,
        provider, 
        LocalClassifierTypeSettings.Default, 
        ErrorReporter.DO_NOTHING,
        LookupTracker.DO_NOTHING, NullFlexibleTypeDeserializer,
        emptyList(), notFoundClasses)

        for (packageFragment in packageFragments) {
            packageFragment.components = components
        }

    return provider
}

public fun parsePackageFragment(packageData: ByteArray): PackageFragment =
    PackageFragment.parseFrom(packageData, 
        KonanSerializerProtocol.extensionRegistry)

public fun parseModuleHeader(libraryData: ByteArray): Library =
    Library.parseFrom(libraryData, 
        KonanSerializerProtocol.extensionRegistry)

internal fun deserializeModule(languageVersionSettings: LanguageVersionSettings,
                               packageLoader:(String)->ByteArray, library: ByteArray,
                               interopLibrary: InteropLibrary?): ModuleDescriptorImpl {

    val libraryProto = parseModuleHeader(library)
    val moduleName = libraryProto.moduleName

    val storageManager = LockBasedStorageManager()
    val builtIns = KonanBuiltIns(storageManager)
    val moduleDescriptor = ModuleDescriptorImpl(
            Name.special(moduleName), storageManager, builtIns)
    builtIns.builtInsModule = moduleDescriptor
    val deserializationConfiguration = CompilerDeserializationConfiguration(languageVersionSettings)

    val provider = createKonanPackageFragmentProvider(
        libraryProto.packageFragmentNameList,
        {it -> parsePackageFragment(packageLoader(it))},
        storageManager, 
        moduleDescriptor, deserializationConfiguration, interopLibrary)

    moduleDescriptor.initialize(provider)

    return moduleDescriptor
}


/* ------------ Serializer part ------------------------------------------*/

internal class KonanSerializationUtil(val context: Context) {

    val serializerExtension = KonanSerializerExtension(context)
    val topSerializer = KonanDescriptorSerializer.createTopLevel(serializerExtension)
    var classSerializer: KonanDescriptorSerializer = topSerializer

    fun serializeClass(packageName: FqName,
        builder: KonanLinkData.Classes.Builder,  
        classDescriptor: ClassDescriptor) {

        val previousSerializer = classSerializer

        // TODO: this is to filter out object{}. Change me.
        if (classDescriptor.isExported()) 
            classSerializer = KonanDescriptorSerializer.create(classDescriptor, serializerExtension)

        val classProto = classSerializer.classProto(classDescriptor).build()
            ?: error("Class not serialized: $classDescriptor")

        builder.addClasses(classProto)
        val index = classSerializer.stringTable.getFqNameIndex(classDescriptor)
        builder.addClassName(index)

        serializeClasses(packageName, builder, 
            classDescriptor.unsubstitutedInnerClassesScope
                .getContributedDescriptors(DescriptorKindFilter.CLASSIFIERS))

        classSerializer = previousSerializer
    }

    fun serializeClasses(packageName: FqName, 
        builder: KonanLinkData.Classes.Builder, 
        descriptors: Collection<DeclarationDescriptor>) {

        for (descriptor in descriptors) {
            if (descriptor is ClassDescriptor) {
                serializeClass(packageName, builder, descriptor)
            }
        }
    }

    fun serializePackage(fqName: FqName, module: ModuleDescriptor) : 
        KonanLinkData.PackageFragment? {

        val packageView = module.getPackage(fqName)

        // TODO: ModuleDescriptor should be able to return 
        // the package only with the contents of that module, without dependencies
        val keep: (DeclarationDescriptor) -> Boolean = 
            { DescriptorUtils.getContainingModule(it) == module }

        val fragments = packageView.fragments
        if (fragments.filter(keep).isEmpty()) return null

        val classifierDescriptors = KonanDescriptorSerializer
            .sort(packageView.memberScope.getContributedDescriptors(DescriptorKindFilter.CLASSIFIERS))
            .filter(keep)

        val members = fragments
                .flatMap { fragment -> DescriptorUtils.getAllDescriptors(fragment.getMemberScope()) }
                .filter(keep)

        val classesBuilder = KonanLinkData.Classes.newBuilder()

        serializeClasses(fqName, classesBuilder, classifierDescriptors)
        val classesProto = classesBuilder.build()

        val packageProto = topSerializer.packagePartProto(fqName, members).build()
            ?: error("Package fragments not serialized: $fragments")

        val strings = serializerExtension.stringTable
        val (stringTableProto, nameTableProto) = strings.buildProto()

        val fragmentBuilder = KonanLinkData.PackageFragment.newBuilder()

        val fragmentProto = fragmentBuilder
            .setPackage(packageProto)
            .setFqName(fqName.asString())
            .setClasses(classesProto)
            .setStringTable(stringTableProto)
            .setNameTable(nameTableProto)
            .build()

        return fragmentProto
    }

    private fun getPackagesFqNames(module: ModuleDescriptor): Set<FqName> {
        val fqNames = mutableSetOf<FqName>(FqName.ROOT)
        getSubPackagesFqNames(module.getPackage(FqName.ROOT), fqNames)
        return fqNames
    }
    private fun getSubPackagesFqNames(packageView: PackageViewDescriptor, result: MutableSet<FqName>) {
        val fqName = packageView.fqName
        if (!fqName.isRoot) {
            result.add(fqName)
        }

        for (descriptor in packageView.memberScope.getContributedDescriptors(
                DescriptorKindFilter.PACKAGES, MemberScope.ALL_NAME_FILTER)) {
            if (descriptor is PackageViewDescriptor) {
                getSubPackagesFqNames(descriptor, result)
            }
        }
    }
    internal fun serializeModule(moduleDescriptor: ModuleDescriptor): LinkData {
        val libraryProto = KonanLinkData.Library.newBuilder()
        libraryProto.moduleName = moduleDescriptor.name.asString()
        val fragments = mutableListOf<ByteArray>()
        val fragmentNames = mutableListOf<String>()

        getPackagesFqNames(moduleDescriptor).forEach iteration@ {
            val packageProto = serializePackage(it, moduleDescriptor)
            if (packageProto == null) return@iteration

            libraryProto.addPackageFragmentName(it.asString())
            fragments.add(packageProto.toByteArray())
            fragmentNames.add(it.asString())
        }
        val libraryAsByteArray = libraryProto.build().toByteArray()
        return LinkData(libraryAsByteArray, fragments, fragmentNames)
    }
}

