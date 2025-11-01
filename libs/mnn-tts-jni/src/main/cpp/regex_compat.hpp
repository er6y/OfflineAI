/**
 * @file regex_compat.hpp
 * @brief Cross-platform regex compatibility layer
 * 
 * Android: Uses RE2 (no std::locale dependency, faster)
 * Others: Uses std::regex (standard library)
 */
#ifndef MNN_TTS_REGEX_COMPAT_HPP
#define MNN_TTS_REGEX_COMPAT_HPP

#ifdef __ANDROID__
    // Android: Use RE2 library (no locale dependency)
    #include <re2/re2.h>
    #include <string>
    #include <vector>
    
    // Type alias
    using mnn_regex = RE2;
    
    // Match result wrapper (compatible with std::smatch)
    class mnn_smatch {
    private:
        std::string full_match_;
        std::vector<std::string> groups_;
        size_t position_;
        std::string prefix_str_;
        std::string suffix_str_;
        std::string::const_iterator suffix_first_;
        
    public:
        mnn_smatch() : position_(0), suffix_first_() {}
        
        void set_match(const std::string& match, size_t pos) {
            full_match_ = match;
            position_ = pos;
        }
        
        void set_prefix(const std::string& prefix) {
            prefix_str_ = prefix;
        }
        
        void set_suffix(std::string::const_iterator first) {
            suffix_first_ = first;
        }
        
        void add_group(const std::string& group) {
            groups_.push_back(group);
        }
        
        // Sub-match wrapper to support .str() method
        struct sub_match {
            std::string value_;
            sub_match(const std::string& v) : value_(v) {}
            std::string str() const { return value_; }
            operator std::string() const { return value_; }
        };
        
        std::string str(size_t index = 0) const {
            if (index == 0) return full_match_;
            if (index <= groups_.size()) return groups_[index - 1];
            return "";
        }
        
        sub_match operator[](size_t index) const {
            return sub_match(str(index));
        }
        
        size_t position() const { return position_; }
        size_t size() const { return groups_.size() + 1; }
        size_t length() const { return full_match_.length(); }
        
        // For compatibility with std::smatch
        struct prefix_type {
            std::string str_;
            prefix_type(const std::string& s) : str_(s) {}
            std::string str() const { return str_; }
        };
        
        struct suffix_type {
            std::string::const_iterator first;
            suffix_type(std::string::const_iterator f) : first(f) {}
        };
        
        prefix_type prefix() const {
            return prefix_type(prefix_str_);
        }
        
        suffix_type suffix() const {
            return suffix_type(suffix_first_);
        }
    };
    
    // Iterator for finding all matches (compatible with std::sregex_iterator)
    class mnn_sregex_iterator {
    private:
        std::string text_;
        const RE2* pattern_;
        re2::StringPiece input_;
        mnn_smatch current_match_;
        bool is_end_;
        
        void find_next() {
            re2::StringPiece match;
            if (RE2::FindAndConsume(&input_, *pattern_, &match)) {
                size_t pos = match.data() - text_.data();
                current_match_.set_match(match.as_string(), pos);
                is_end_ = false;
            } else {
                is_end_ = true;
            }
        }
        
    public:
        mnn_sregex_iterator() : pattern_(nullptr), is_end_(true) {}
        
        mnn_sregex_iterator(std::string::const_iterator begin,
                           std::string::const_iterator end,
                           const RE2& pattern)
            : text_(begin, end), pattern_(&pattern), input_(text_), is_end_(false) {
            find_next();
        }
        
        mnn_sregex_iterator& operator++() {
            if (!is_end_) find_next();
            return *this;
        }
        
        bool operator!=(const mnn_sregex_iterator& other) const {
            return is_end_ != other.is_end_;
        }
        
        const mnn_smatch& operator*() const {
            return current_match_;
        }
        
        const mnn_smatch* operator->() const {
            return &current_match_;
        }
    };
    
    // Token iterator for splitting (compatible with std::sregex_token_iterator)
    class mnn_sregex_token_iterator {
    private:
        std::vector<std::string> tokens_;
        size_t current_index_;
        bool is_end_;
        
    public:
        // Token wrapper to support ->str() syntax
        struct token_ref {
            std::string value_;
            token_ref(const std::string& v) : value_(v) {}
            std::string str() const { return value_; }
            const token_ref* operator->() const { return this; }
        };
        
        mnn_sregex_token_iterator() : current_index_(0), is_end_(true) {}
        
        mnn_sregex_token_iterator(std::string::const_iterator begin,
                                 std::string::const_iterator end,
                                 const RE2& pattern,
                                 int submatch = -1)
            : current_index_(0), is_end_(false) {
            
            std::string text(begin, end);
            
            if (submatch == -1) {
                // Split mode: return parts between matches
                re2::StringPiece input(text);
                re2::StringPiece match;
                size_t last_pos = 0;
                
                while (RE2::FindAndConsume(&input, pattern, &match)) {
                    size_t match_start = match.data() - text.data();
                    if (match_start > last_pos) {
                        tokens_.push_back(text.substr(last_pos, match_start - last_pos));
                    }
                    last_pos = match.data() - text.data() + match.size();
                }
                
                // Add remaining text
                if (last_pos < text.size()) {
                    tokens_.push_back(text.substr(last_pos));
                }
            }
            
            if (tokens_.empty()) is_end_ = true;
        }
        
        mnn_sregex_token_iterator& operator++() {
            if (++current_index_ >= tokens_.size()) {
                is_end_ = true;
            }
            return *this;
        }
        
        bool operator!=(const mnn_sregex_token_iterator& other) const {
            return is_end_ != other.is_end_;
        }
        
        const std::string& operator*() const {
            return tokens_[current_index_];
        }
        
        token_ref operator->() const {
            return token_ref(tokens_[current_index_]);
        }
    };
    
    // Wrapper functions for RE2
    inline bool mnn_regex_match(const std::string& text, const RE2& pattern) {
        return RE2::FullMatch(text, pattern);
    }
    
    inline bool mnn_regex_search(const std::string& text, const RE2& pattern) {
        return RE2::PartialMatch(text, pattern);
    }
    
    inline bool mnn_regex_search(std::string::const_iterator begin,
                                std::string::const_iterator end,
                                mnn_smatch& match,
                                const RE2& pattern) {
        std::string text(begin, end);
        re2::StringPiece input(text);
        re2::StringPiece sp_match;
        
        if (RE2::FindAndConsume(&input, pattern, &sp_match)) {
            size_t pos = sp_match.data() - text.data();
            match.set_match(sp_match.as_string(), pos);
            match.set_prefix(text.substr(0, pos));
            
            // Calculate suffix iterator
            size_t match_end = pos + sp_match.size();
            auto suffix_it = begin;
            std::advance(suffix_it, match_end);
            match.set_suffix(suffix_it);
            
            return true;
        }
        return false;
    }
    
    inline std::string mnn_regex_replace(const std::string& text,
                                        const RE2& pattern,
                                        const std::string& replacement) {
        std::string result = text;
        RE2::GlobalReplace(&result, pattern, replacement);
        return result;
    }

#else
    // Other platforms: Use standard library
    #include <regex>
    
    // Type alias
    using mnn_regex = std::regex;
    using mnn_smatch = std::smatch;
    using mnn_sregex_iterator = std::sregex_iterator;
    using mnn_sregex_token_iterator = std::sregex_token_iterator;
    
    // Direct mapping
    inline bool mnn_regex_match(const std::string& text, const std::regex& pattern) {
        return std::regex_match(text, pattern);
    }
    
    inline bool mnn_regex_search(const std::string& text, const std::regex& pattern) {
        return std::regex_search(text, pattern);
    }
    
    inline bool mnn_regex_search(std::string::const_iterator begin,
                                std::string::const_iterator end,
                                std::smatch& match,
                                const std::regex& pattern) {
        return std::regex_search(begin, end, match, pattern);
    }
    
    inline std::string mnn_regex_replace(const std::string& text,
                                        const std::regex& pattern,
                                        const std::string& replacement) {
        return std::regex_replace(text, pattern, replacement);
    }
#endif

#endif // MNN_TTS_REGEX_COMPAT_HPP
