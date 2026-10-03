//! A small in-memory file store for the simulated phone (protocol 1.6):
//! two allowed photos, a picked folder with a subfolder, and an empty
//! Downloads. Behaves like the Android app: opaque ids, free names instead
//! of silent overwrites, appends only to files this session created, and
//! changes only where the owner allowed them.

use latch_protocol::{
    ErrorCode, FileChunk, FileItem, FileKind, FileList, FileLocation, FilePreview, ProtocolError,
    Screenshot, b64,
};

#[derive(Debug, Clone)]
pub struct FakeFile {
    pub id: String,
    pub name: String,
    pub location: FileLocation,
    /// Folder id inside the picked folder; `None` is the top level.
    pub parent: Option<String>,
    pub folder: bool,
    pub mime: Option<String>,
    pub data: Vec<u8>,
    /// Saved by Latch in this session (may be appended to, renamed, deleted anywhere).
    pub by_latch: bool,
    pub modified_ms: u64,
}

#[derive(Debug, Clone)]
pub struct FakeFiles {
    pub files: Vec<FakeFile>,
    next: u32,
}

/// One white pixel.
const PIXEL_JPEG_STAND_IN: &[u8] = b"\xff\xd8\xff fake jpeg";

fn err(code: ErrorCode, message: &str) -> ProtocolError {
    ProtocolError::new(code, message)
}

impl Default for FakeFiles {
    fn default() -> Self {
        let mut f = FakeFiles {
            files: Vec::new(),
            next: 0,
        };
        f.add(
            FileLocation::Photos,
            None,
            "IMG_0001.jpg",
            Some("image/jpeg"),
            PIXEL_JPEG_STAND_IN,
            false,
        );
        f.add(
            FileLocation::Photos,
            None,
            "IMG_0002.jpg",
            Some("image/jpeg"),
            PIXEL_JPEG_STAND_IN,
            false,
        );
        let to_post = f.add_folder(None, "ToPost", false);
        f.add(
            FileLocation::Folder,
            Some(to_post.clone()),
            "beach.jpg",
            Some("image/jpeg"),
            PIXEL_JPEG_STAND_IN,
            false,
        );
        f.add(
            FileLocation::Folder,
            Some(to_post),
            "caption.txt",
            Some("text/plain"),
            b"Sunset at the beach",
            false,
        );
        f.add(
            FileLocation::Folder,
            None,
            "notes.txt",
            Some("text/plain"),
            b"hello from the phone",
            false,
        );
        f
    }
}

impl FakeFiles {
    fn new_id(&mut self) -> String {
        self.next += 1;
        format!("f_{:04}", self.next)
    }

    fn add(
        &mut self,
        location: FileLocation,
        parent: Option<String>,
        name: &str,
        mime: Option<&str>,
        data: &[u8],
        by_latch: bool,
    ) -> String {
        let id = self.new_id();
        self.files.push(FakeFile {
            id: id.clone(),
            name: name.into(),
            location,
            parent,
            folder: false,
            mime: mime.map(str::to_owned),
            data: data.to_vec(),
            by_latch,
            modified_ms: 1_791_000_000_000 + u64::from(self.next),
        });
        id
    }

    fn add_folder(&mut self, parent: Option<String>, name: &str, by_latch: bool) -> String {
        let id = self.new_id();
        self.files.push(FakeFile {
            id: id.clone(),
            name: name.into(),
            location: FileLocation::Folder,
            parent,
            folder: true,
            mime: None,
            data: Vec::new(),
            by_latch,
            modified_ms: 1_791_000_000_000 + u64::from(self.next),
        });
        id
    }

    fn item(f: &FakeFile) -> FileItem {
        let kind = match (f.folder, f.mime.as_deref()) {
            (true, _) => FileKind::Folder,
            (_, Some(m)) if m.starts_with("image/") => FileKind::Image,
            (_, Some(m)) if m.starts_with("video/") => FileKind::Video,
            _ => FileKind::File,
        };
        FileItem {
            id: f.id.clone(),
            name: f.name.clone(),
            kind,
            location: f.location,
            mime: f.mime.clone(),
            size: (!f.folder).then_some(f.data.len() as u64),
            modified_ms: Some(f.modified_ms),
        }
    }

    fn get(&self, id: &str) -> Result<&FakeFile, ProtocolError> {
        self.files.iter().find(|f| f.id == id).ok_or_else(|| {
            err(
                ErrorCode::TargetNotFound,
                "no file with that id; list files again",
            )
        })
    }

    fn folder(&self, id: Option<&str>) -> Result<Option<String>, ProtocolError> {
        match id {
            None => Ok(None),
            Some(id) => {
                let f = self.get(id)?;
                if !f.folder {
                    return Err(err(
                        ErrorCode::InvalidRequest,
                        "that id is a file, not a folder",
                    ));
                }
                Ok(Some(f.id.clone()))
            }
        }
    }

    pub fn list(
        &self,
        location: FileLocation,
        folder: Option<&str>,
        query: Option<&str>,
        limit: u32,
        offset: u32,
    ) -> Result<FileList, ProtocolError> {
        let parent = if location == FileLocation::Folder {
            self.folder(folder)?
        } else {
            None
        };
        let query = query.map(str::to_lowercase);
        let mut matching: Vec<&FakeFile> = self
            .files
            .iter()
            .filter(|f| f.location == location)
            .filter(|f| location != FileLocation::Folder || f.parent == parent)
            .filter(|f| {
                query
                    .as_ref()
                    .is_none_or(|q| f.name.to_lowercase().contains(q))
            })
            .collect();
        matching.sort_by_key(|f| std::cmp::Reverse(f.modified_ms));
        let total = matching.len() as u32;
        let items: Vec<FileItem> = matching
            .iter()
            .skip(offset as usize)
            .take(limit as usize)
            .map(|f| Self::item(f))
            .collect();
        let end = offset + items.len() as u32;
        Ok(FileList {
            location,
            folder_name: match (&parent, location) {
                (Some(id), _) => Some(self.get(id)?.name.clone()),
                (None, FileLocation::Folder) => Some("Phone files".into()),
                _ => None,
            },
            items,
            total,
            next_offset: (end < total).then_some(end),
        })
    }

    pub fn preview(&self, id: &str) -> Result<FilePreview, ProtocolError> {
        let f = self.get(id)?;
        if f.folder {
            return Err(err(
                ErrorCode::InvalidRequest,
                "that is a folder; list it instead",
            ));
        }
        let mime = f.mime.as_deref().unwrap_or("");
        Ok(FilePreview {
            item: Self::item(f),
            text: mime
                .starts_with("text/")
                .then(|| String::from_utf8_lossy(&f.data).into_owned()),
            text_truncated: false,
            image: mime.starts_with("image/").then(|| Screenshot {
                mime: "image/jpeg".into(),
                width: 1,
                height: 1,
                data_base64: b64::encode(&f.data),
            }),
        })
    }

    pub fn read(&self, id: &str, offset: u64, length: u32) -> Result<FileChunk, ProtocolError> {
        let f = self.get(id)?;
        if f.folder {
            return Err(err(
                ErrorCode::InvalidRequest,
                "that is a folder; list it instead",
            ));
        }
        let start = (offset as usize).min(f.data.len());
        let end = (start + length as usize).min(f.data.len());
        Ok(FileChunk {
            item: Self::item(f),
            offset: start as u64,
            data_base64: b64::encode(&f.data[start..end]),
            eof: end == f.data.len(),
        })
    }

    /// A name not yet used in that place: "a.txt", then "a (1).txt", ...
    fn free_name(&self, location: FileLocation, parent: &Option<String>, name: &str) -> String {
        let taken = |n: &str| {
            self.files
                .iter()
                .any(|f| f.location == location && &f.parent == parent && f.name == n)
        };
        if !taken(name) {
            return name.into();
        }
        let (stem, ext) = match name.rfind('.') {
            Some(i) if i > 0 => (&name[..i], &name[i..]),
            _ => (name, ""),
        };
        (1..)
            .map(|i| format!("{stem} ({i}){ext}"))
            .find(|n| !taken(n))
            .unwrap_or_else(|| name.into())
    }

    #[allow(clippy::too_many_arguments)]
    pub fn write(
        &mut self,
        location: FileLocation,
        folder: Option<&str>,
        subfolder: Option<&str>,
        name: &str,
        mime: Option<&str>,
        data_base64: &str,
        append: bool,
        overwrite: bool,
    ) -> Result<FileItem, ProtocolError> {
        let data = b64::decode(data_base64)
            .ok_or_else(|| err(ErrorCode::InvalidRequest, "data_base64 is not valid base64"))?;
        if location == FileLocation::Photos
            && !mime.is_some_and(|m| m.starts_with("image/") || m.starts_with("video/"))
        {
            return Err(err(
                ErrorCode::InvalidRequest,
                "photos take images and videos only",
            ));
        }
        let parent = match location {
            FileLocation::Folder => self.folder(folder)?,
            // Photos and Downloads keep subfolders as part of where they are saved.
            _ => Some(subfolder.unwrap_or("Latch").to_owned()),
        };
        let existing = self.files.iter().position(|f| {
            f.location == location && f.parent == parent && f.name == name && !f.folder
        });
        if append {
            let i = existing
                .filter(|&i| self.files[i].by_latch)
                .ok_or_else(|| {
                    err(
                        ErrorCode::TargetNotFound,
                        "append only to a file Latch saved in this session",
                    )
                })?;
            self.files[i].data.extend_from_slice(&data);
            return Ok(Self::item(&self.files[i]));
        }
        if let (Some(i), true) = (existing, overwrite) {
            if location != FileLocation::Folder && !self.files[i].by_latch {
                return Err(err(
                    ErrorCode::PolicyRefused,
                    "Latch replaces only files it saved there",
                ));
            }
            self.files[i].data = data;
            self.files[i].mime = mime.map(str::to_owned);
            return Ok(Self::item(&self.files[i]));
        }
        let name = self.free_name(location, &parent, name);
        let id = self.add(location, parent, &name, mime, &data, true);
        Ok(Self::item(self.get(&id)?))
    }

    pub fn mkdir(&mut self, folder: Option<&str>, name: &str) -> Result<FileItem, ProtocolError> {
        let parent = self.folder(folder)?;
        let name = self.free_name(FileLocation::Folder, &parent, name);
        let id = self.add_folder(parent, &name, true);
        Ok(Self::item(self.get(&id)?))
    }

    fn changeable(&self, id: &str) -> Result<usize, ProtocolError> {
        let i = self.files.iter().position(|f| f.id == id).ok_or_else(|| {
            err(
                ErrorCode::TargetNotFound,
                "no file with that id; list files again",
            )
        })?;
        let f = &self.files[i];
        if f.location != FileLocation::Folder && !f.by_latch {
            return Err(err(
                ErrorCode::PolicyRefused,
                "in photos and Downloads Latch changes only files it saved",
            ));
        }
        Ok(i)
    }

    pub fn rename(&mut self, id: &str, name: &str) -> Result<FileItem, ProtocolError> {
        let i = self.changeable(id)?;
        let (location, parent) = (self.files[i].location, self.files[i].parent.clone());
        let name = self.free_name(location, &parent, name);
        self.files[i].name = name;
        Ok(Self::item(&self.files[i]))
    }

    pub fn delete(&mut self, id: &str) -> Result<(), ProtocolError> {
        let i = self.changeable(id)?;
        let gone = self.files.remove(i);
        if gone.folder {
            self.files
                .retain(|f| f.parent.as_deref() != Some(gone.id.as_str()));
        }
        Ok(())
    }

    pub fn name_of(&self, id: &str) -> Result<String, ProtocolError> {
        let f = self.get(id)?;
        if f.folder {
            return Err(err(ErrorCode::InvalidRequest, "share files, not folders"));
        }
        Ok(f.name.clone())
    }
}
