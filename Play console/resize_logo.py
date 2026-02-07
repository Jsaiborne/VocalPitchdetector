from PIL import Image

def resize_image(input_path, output_path, size=(512, 512)):
    try:
        with Image.open(input_path) as img:
            resized_img = img.resize(size, Image.Resampling.LANCZOS)
            resized_img.save(output_path)
            print(f"Success! Image saved as {output_path}")
    except FileNotFoundError:
        print("Error: 'logo.png' not found.")
    except Exception as e:
        print(f"An error occurred: {e}")

if __name__ == "__main__":
    resize_image('logo.png', 'logo_512.png')
