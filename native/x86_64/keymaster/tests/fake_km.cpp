/*
 * Stand-in for keymaster::AndroidKeymaster, for the HOST test only (run-host-test.sh): the real class lives in
 * libkeymaster_portable.so, which needs BoringSSL and the guest's bionic. The message classes (serialization) are AOSP's own
 * sources, compiled natively, so what is under test is exactly what the answerer adds: the 12-byte frame, the command
 * dispatch, the message-version handling, the by-value / by-pointer response paths and the error answers.
 */
#include <string.h>
#include <algorithm>
#include <string>
#include <keymaster/android_keymaster.h>

namespace keymaster {

/* the two types AndroidKeymaster only holds by unique_ptr: complete here (their real headers need cppbor), unused */
class KeymasterContext { public: virtual ~KeymasterContext() {} };
class OperationTable { public: virtual ~OperationTable() {} };

static uint32_t g_os_patchlevel = 0;
static keymaster_operation_handle_t kHandle = 42;

AndroidKeymaster::AndroidKeymaster(KeymasterContext*, size_t, uint32_t message_version)
    : context_(nullptr), operation_table_(nullptr), message_version_(message_version) {}
AndroidKeymaster::~AndroidKeymaster() {}

void AndroidKeymaster::GetVersion(const GetVersionRequest&, GetVersionResponse* rsp) {
    rsp->error = KM_ERROR_OK;
    rsp->major_ver = 2;
    rsp->minor_ver = 0;
    rsp->subminor_ver = g_os_patchlevel & 0xff;
}
void AndroidKeymaster::Configure(const ConfigureRequest& req, ConfigureResponse* rsp) {
    g_os_patchlevel = req.os_patchlevel;
    rsp->error = KM_ERROR_OK;
}
void AndroidKeymaster::AddRngEntropy(const AddEntropyRequest&, AddEntropyResponse* rsp) { rsp->error = KM_ERROR_OK; }
void AndroidKeymaster::GenerateKey(const GenerateKeyRequest& req, GenerateKeyResponse* rsp) {
    size_t n = req.key_description.SerializedSize();
    uint8_t* b = new uint8_t[n];
    req.key_description.Serialize(b, b + n);
    rsp->key_blob = KeymasterKeyBlob(b, n);  // (copies)
    delete[] b;
    rsp->enforced.Reinitialize(req.key_description);
    rsp->error = KM_ERROR_OK;
}
void AndroidKeymaster::BeginOperation(const BeginOperationRequest& req, BeginOperationResponse* rsp) {
    rsp->op_handle = kHandle;
    rsp->error = req.key_blob.key_material_size ? KM_ERROR_OK : KM_ERROR_INVALID_KEY_BLOB;
}
void AndroidKeymaster::UpdateOperation(const UpdateOperationRequest& req, UpdateOperationResponse* rsp) {
    if (req.op_handle != kHandle) { rsp->error = KM_ERROR_INVALID_OPERATION_HANDLE; return; }
    size_t n = req.input.available_read();
    std::string s(reinterpret_cast<const char*>(req.input.peek_read()), n);
    std::reverse(s.begin(), s.end());
    rsp->output.Reinitialize(s.data(), s.size());
    rsp->input_consumed = n;
    rsp->error = KM_ERROR_OK;
}
void AndroidKeymaster::FinishOperation(const FinishOperationRequest& req, FinishOperationResponse* rsp) {
    if (req.op_handle != kHandle) { rsp->error = KM_ERROR_INVALID_OPERATION_HANDLE; return; }
    rsp->output.Reinitialize("done", 4);
    rsp->error = KM_ERROR_OK;
}
void AndroidKeymaster::AbortOperation(const AbortOperationRequest&, AbortOperationResponse* rsp) { rsp->error = KM_ERROR_OK; }
void AndroidKeymaster::DeleteKey(const DeleteKeyRequest&, DeleteKeyResponse* rsp) { rsp->error = KM_ERROR_OK; }
void AndroidKeymaster::DeleteAllKeys(const DeleteAllKeysRequest&, DeleteAllKeysResponse* rsp) { rsp->error = KM_ERROR_OK; }

GetHmacSharingParametersResponse AndroidKeymaster::GetHmacSharingParameters() {
    GetHmacSharingParametersResponse r(message_version_);
    r.error = KM_ERROR_OK;
    memset(r.params.nonce, 0xAB, sizeof r.params.nonce);
    r.params.seed = KeymasterBlob(reinterpret_cast<const uint8_t*>("seed"), 4);
    return r;
}
EarlyBootEndedResponse AndroidKeymaster::EarlyBootEnded() {
    EarlyBootEndedResponse r(message_version_);
    r.error = KM_ERROR_OK;
    return r;
}
DeviceLockedResponse AndroidKeymaster::DeviceLocked(const DeviceLockedRequest&) {
    DeviceLockedResponse r(message_version_);
    r.error = KM_ERROR_OK;
    return r;
}
GetVersion2Response AndroidKeymaster::GetVersion2(const GetVersion2Request&) {
    GetVersion2Response r(message_version_);
    r.error = KM_ERROR_OK;
    r.max_message_version = 4;
    r.km_version = KmVersion::KEYMASTER_4_1;
    r.km_date = 20221101;
    message_version_ = 3;
    return r;
}

/* the rest of the interface: not used by the host test, answered UNIMPLEMENTED */
#define UNIMPL_VOID(NAME, REQ, RSP) void AndroidKeymaster::NAME(const REQ&, RSP* r) { r->error = KM_ERROR_UNIMPLEMENTED; }
UNIMPL_VOID(SupportedAlgorithms, SupportedAlgorithmsRequest, SupportedAlgorithmsResponse)
UNIMPL_VOID(SupportedBlockModes, SupportedBlockModesRequest, SupportedBlockModesResponse)
UNIMPL_VOID(SupportedPaddingModes, SupportedPaddingModesRequest, SupportedPaddingModesResponse)
UNIMPL_VOID(SupportedDigests, SupportedDigestsRequest, SupportedDigestsResponse)
UNIMPL_VOID(SupportedImportFormats, SupportedImportFormatsRequest, SupportedImportFormatsResponse)
UNIMPL_VOID(SupportedExportFormats, SupportedExportFormatsRequest, SupportedExportFormatsResponse)
UNIMPL_VOID(GenerateRkpKey, GenerateRkpKeyRequest, GenerateRkpKeyResponse)
UNIMPL_VOID(GenerateCsr, GenerateCsrRequest, GenerateCsrResponse)
UNIMPL_VOID(GetKeyCharacteristics, GetKeyCharacteristicsRequest, GetKeyCharacteristicsResponse)
UNIMPL_VOID(ImportKey, ImportKeyRequest, ImportKeyResponse)
UNIMPL_VOID(ImportWrappedKey, ImportWrappedKeyRequest, ImportWrappedKeyResponse)
UNIMPL_VOID(ExportKey, ExportKeyRequest, ExportKeyResponse)
UNIMPL_VOID(AttestKey, AttestKeyRequest, AttestKeyResponse)
UNIMPL_VOID(UpgradeKey, UpgradeKeyRequest, UpgradeKeyResponse)
void AndroidKeymaster::GenerateTimestampToken(GenerateTimestampTokenRequest&, GenerateTimestampTokenResponse* r) {
    r->error = KM_ERROR_UNIMPLEMENTED;
}
ComputeSharedHmacResponse AndroidKeymaster::ComputeSharedHmac(const ComputeSharedHmacRequest&) {
    ComputeSharedHmacResponse r(message_version_);
    r.error = KM_ERROR_UNIMPLEMENTED;
    return r;
}
VerifyAuthorizationResponse AndroidKeymaster::VerifyAuthorization(const VerifyAuthorizationRequest&) {
    VerifyAuthorizationResponse r(message_version_);
    r.error = KM_ERROR_UNIMPLEMENTED;
    return r;
}
ConfigureVendorPatchlevelResponse AndroidKeymaster::ConfigureVendorPatchlevel(const ConfigureVendorPatchlevelRequest&) {
    ConfigureVendorPatchlevelResponse r(message_version_);
    r.error = KM_ERROR_OK;
    return r;
}
ConfigureBootPatchlevelResponse AndroidKeymaster::ConfigureBootPatchlevel(const ConfigureBootPatchlevelRequest&) {
    ConfigureBootPatchlevelResponse r(message_version_);
    r.error = KM_ERROR_OK;
    return r;
}
bool AndroidKeymaster::has_operation(keymaster_operation_handle_t) const { return false; }

}  // namespace keymaster

/* the context constructor the answerer calls by its mangled name in the guest */
extern "C" void aemu_psc_ctor(void*, keymaster::KmVersion, keymaster_security_level_t) {}
