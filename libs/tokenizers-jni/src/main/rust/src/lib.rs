use jni::sys::{jlong, jstring};
use jni::{JNIEnv, objects::JClass, objects::JString};
use tokenizers::Tokenizer;
use tokenizers::models::{bpe::BPE, wordpiece::WordPiece};
use serde_json;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_void};

// C风格FFI函数 - 供C++代码调用

// 创建分词器 - C API
#[no_mangle]
pub extern "C" fn create_tokenizer(model_type: *const c_char) -> *mut c_void {
    let model_type_str = unsafe {
        if model_type.is_null() {
            return std::ptr::null_mut();
        }
        match CStr::from_ptr(model_type).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };
    
    let tokenizer = match model_type_str {
        "bpe" => {
            let bpe = BPE::builder()
                .unk_token("[UNK]".into())
                .build()
                .unwrap();
            Tokenizer::new(bpe)
        },
        "wordpiece" => {
            let wordpiece = WordPiece::builder()
                .unk_token("[UNK]".into())
                .build()
                .unwrap();
            Tokenizer::new(wordpiece)
        },
        _ => return std::ptr::null_mut(),
    };
    
    Box::into_raw(Box::new(tokenizer)) as *mut c_void
}

// 从文件加载分词器 - C API
#[no_mangle]
pub extern "C" fn load_tokenizer_from_file(path: *const c_char) -> *mut c_void {
    let path_str = unsafe {
        if path.is_null() {
            return std::ptr::null_mut();
        }
        match CStr::from_ptr(path).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };
    
    let tokenizer = match Tokenizer::from_file(path_str) {
        Ok(t) => t,
        Err(_) => return std::ptr::null_mut(),
    };
    
    Box::into_raw(Box::new(tokenizer)) as *mut c_void
}

// 分词 - C API
#[no_mangle]
pub extern "C" fn tokenize(tokenizer_ptr: *mut c_void, text: *const c_char) -> *mut c_char {
    if tokenizer_ptr.is_null() || text.is_null() {
        return std::ptr::null_mut();
    }
    
    let tokenizer = unsafe { &mut *(tokenizer_ptr as *mut Tokenizer) };
    let text_str = unsafe {
        match CStr::from_ptr(text).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };
    
    let encoding = match tokenizer.encode(text_str, false) {
        Ok(e) => e,
        Err(_) => return std::ptr::null_mut(),
    };
    
    let json = match serde_json::to_string(&encoding) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    
    match CString::new(json) {
        Ok(c_str) => c_str.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// 释放字符串 - C API
#[no_mangle]
pub extern "C" fn free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe { drop(CString::from_raw(ptr)) };
    }
}

// 释放分词器 - C API
#[no_mangle]
pub extern "C" fn free_tokenizer(tokenizer_ptr: *mut c_void) {
    if !tokenizer_ptr.is_null() {
        unsafe {
            let _ = Box::from_raw(tokenizer_ptr as *mut Tokenizer);
        }
    }
}

// 直接JNI实现 - 创建分词器
#[no_mangle]
pub extern "system" fn Java_com_starlocalrag_tokenizers_TokenizerJNI_createTokenizer(
    env: JNIEnv,
    _class: JClass,
    model_type: JString,
) -> jlong {

    
    let model_type_str: String = match env.get_string(model_type) {
        Ok(s) => {
            let str_val = s.into();
    
            str_val
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: 无法获取模型类型字符串: {:?}", e);
            return 0;
        },
    };
    

    
    // 创建基本的分词器
    let tokenizer = match model_type_str.as_str() {
        "bpe" => {
    
            let bpe = BPE::builder()
                .unk_token("[UNK]".into())
                .build()
                .unwrap();
            Tokenizer::new(bpe)
        },
        "wordpiece" => {
    
            let wordpiece = WordPiece::builder()
                .unk_token("[UNK]".into())
                .build()
                .unwrap();
            Tokenizer::new(wordpiece)
        },
        _ => {
            println!("[ERROR] Rust JNI: 不支持的模型类型: {}", model_type_str);
            return 0;
        },
    };
    
    let ptr = Box::into_raw(Box::new(tokenizer)) as jlong;

    ptr
}

// 直接JNI实现 - 从文件加载分词器
#[no_mangle]
pub extern "system" fn Java_com_starlocalrag_tokenizers_TokenizerJNI_loadTokenizerFromFileNative(
    env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {

    
    let path_str: String = match env.get_string(path) {
        Ok(s) => {
            let str_val = s.into();
    
            str_val
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: 无法获取文件路径字符串: {:?}", e);
            return 0;
        },
    };
    

    
    let tokenizer = match Tokenizer::from_file(&path_str) {
        Ok(t) => {

            t
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: 分词器文件加载失败: {:?}", e);
            return 0;
        },
    };
    
    let ptr = Box::into_raw(Box::new(tokenizer)) as jlong;

    ptr
}

// 直接JNI实现 - 分词
#[no_mangle]
pub extern "system" fn Java_com_starlocalrag_tokenizers_TokenizerJNI_tokenize(
    env: JNIEnv,
    _class: JClass,
    tokenizer_ptr: jlong,
    text: JString,
) -> jstring {

    
    if tokenizer_ptr == 0 {
        println!("[ERROR] Rust JNI: 分词器指针为空");
        return std::ptr::null_mut();
    }
    
    let tokenizer = unsafe { &*(tokenizer_ptr as *const Tokenizer) };
    let text_str: String = match env.get_string(text) {
        Ok(s) => {
            let str_val = s.into();
    
            str_val
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: 无法获取文本字符串: {:?}", e);
            return std::ptr::null_mut();
        },
    };
    
    
    
    let encoding = match tokenizer.encode(text_str.as_str(), false) {
        Ok(e) => {

            e
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: 分词失败: {:?}", e);
            return std::ptr::null_mut();
        },
    };
    
    let json = match serde_json::to_string(&encoding) {
        Ok(j) => {

            j
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: JSON序列化失败: {:?}", e);
            return std::ptr::null_mut();
        },
    };
    
    match env.new_string(json) {
        Ok(s) => {

            s.into_inner()
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: 创建JNI字符串失败: {:?}", e);
            std::ptr::null_mut()
        },
    }
}

// 直接JNI实现 - 获取分词器配置
#[no_mangle]
pub extern "system" fn Java_com_starlocalrag_tokenizers_TokenizerJNI_getTokenizerConfig(
    env: JNIEnv,
    _class: JClass,
    tokenizer_ptr: jlong,
) -> jstring {

    
    if tokenizer_ptr == 0 {
        println!("[ERROR] Rust JNI: 分词器指针为空");
        return std::ptr::null_mut();
    }
    
    let tokenizer = unsafe { &*(tokenizer_ptr as *const Tokenizer) };
    
    // 创建配置对象
    let mut config = serde_json::Map::new();
    
    // 获取词汇表
    let vocab = tokenizer.get_vocab(true);
    
    // 添加词汇表大小
    config.insert("vocab_size".to_string(), serde_json::Value::Number(vocab.len().into()));
    
    // 添加词汇表
    let mut vocab_json = serde_json::Map::new();
    for (token, id) in vocab.iter() {
        vocab_json.insert(token.clone(), serde_json::Value::Number((*id).into()));
    }
    config.insert("vocab".to_string(), serde_json::Value::Object(vocab_json));
    
    // 添加所有特殊 token
    let mut added_tokens = Vec::new();
    for (token, id) in vocab.iter() {
        // 检测可能的特殊 token
        if token.starts_with("<") || token.ends_with(">")
           || token.starts_with("[") || token.ends_with("]")
           || token == "user" || token == "assistant" || token == "system" {
            
            let mut token_obj = serde_json::Map::new();
            token_obj.insert("id".to_string(), serde_json::Value::Number((*id).into()));
            token_obj.insert("content".to_string(), serde_json::Value::String(token.clone()));
            token_obj.insert("special".to_string(), serde_json::Value::Bool(true));
            
            added_tokens.push(serde_json::Value::Object(token_obj));
        }
    }
    
    if !added_tokens.is_empty() {
        config.insert("added_tokens".to_string(), serde_json::Value::Array(added_tokens));
    }
    
    // 将配置转换为JSON字符串
    let json = match serde_json::to_string(&config) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    
    match env.new_string(json) {
        Ok(s) => s.into_inner(),
        Err(_) => std::ptr::null_mut(),
    }
}

// 直接JNI实现 - 解码token ID为文本（默认不跳过特殊token）
#[no_mangle]
pub extern "system" fn Java_com_starlocalrag_tokenizers_TokenizerJNI_decode__JLjava_lang_String_2(
    env: JNIEnv,
    _class: JClass,
    tokenizer_ptr: jlong,
    ids_json: JString,
) -> jstring {

    decode_impl(env, tokenizer_ptr, ids_json, false)
}

// 直接JNI实现 - 解码token ID为文本（可选择是否跳过特殊token）
#[no_mangle]
pub extern "system" fn Java_com_starlocalrag_tokenizers_TokenizerJNI_decode__JLjava_lang_String_2Z(
    env: JNIEnv,
    _class: JClass,
    tokenizer_ptr: jlong,
    ids_json: JString,
    skip_special_tokens: bool,
) -> jstring {

    decode_impl(env, tokenizer_ptr, ids_json, skip_special_tokens)
}

// 解码实现的辅助函数
fn decode_impl(
    env: JNIEnv,
    tokenizer_ptr: jlong,
    ids_json: JString,
    skip_special_tokens: bool,
) -> jstring {

    
    if tokenizer_ptr == 0 {
        println!("[ERROR] Rust JNI: decode_impl 分词器指针为空");
        return std::ptr::null_mut();
    }
    
    let tokenizer = unsafe { &*(tokenizer_ptr as *const Tokenizer) };
    let ids_str: String = match env.get_string(ids_json) {
        Ok(s) => {
            let str_val = s.into();
    
            str_val
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: decode_impl 无法获取IDs字符串: {:?}", e);
            return std::ptr::null_mut();
        },
    };
    
    let ids: Vec<u32> = match serde_json::from_str::<Vec<u32>>(&ids_str) {
        Ok(i) => {
    
            i
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: decode_impl JSON解析失败: {:?}", e);
            return std::ptr::null_mut();
        },
    };
    
    let decoded = match tokenizer.decode(&ids, skip_special_tokens) {
        Ok(d) => {
    
            d
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: decode_impl 解码失败: {:?}", e);
            return std::ptr::null_mut();
        },
    };
    
    match env.new_string(decoded) {
        Ok(s) => {
    
            s.into_inner()
        },
        Err(e) => {
            println!("[ERROR] Rust JNI: decode_impl 创建JNI字符串失败: {:?}", e);
            std::ptr::null_mut()
        },
    }
}



// 直接JNI实现 - 释放分词器
#[no_mangle]
pub extern "system" fn Java_com_starlocalrag_tokenizers_TokenizerJNI_freeTokenizer(
    _env: JNIEnv,
    _class: JClass,
    tokenizer_ptr: jlong,
) {

    
    if tokenizer_ptr != 0 {
        unsafe {
            let _ = Box::from_raw(tokenizer_ptr as *mut Tokenizer);
    
        }
    } else {
        println!("[WARNING] Rust JNI: 尝试释放空指针");
    }
}